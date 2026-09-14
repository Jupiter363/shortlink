package main

import (
	"bytes"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net"
	"net/http"
	"net/url"
	"os"
	"path/filepath"
	"regexp"
	"strings"
	"time"
)

const (
	version              = "1.4.1-drained-receipt"
	kind                 = "NATIVE_GO_REDIRECT_GENERATOR"
	maxSamples           = 1200000
	maxResponseWireBytes = 64 * 1024
)

type Link struct {
	ShortURI  string `json:"shortUri"`
	OriginURL string `json:"originUrl"`
}

type Config struct {
	RequireGatewayRequestID bool     `json:"requireGatewayRequestId"`
	PreallocateFDTable      bool     `json:"preallocateFDTable"`
	RunID                   string   `json:"runId"`
	Label                   string   `json:"label"`
	BaseURL                 string   `json:"baseUrl"`
	Host                    string   `json:"host"`
	Sources                 []string `json:"sources"`
	Links                   []Link   `json:"links"`
	Rate                    int      `json:"rate"`
	WarmupMillis            int      `json:"warmupMillis"`
	DurationMillis          int      `json:"durationMillis"`
	WarmupStartRate         int      `json:"warmupStartRate"`
	Workers                 int      `json:"workers"`
	Seed                    uint32   `json:"seed"`
	Output                  string   `json:"output"`
	RequestsDrainedReceipt  string   `json:"requestsDrainedReceipt,omitempty"`
	UserAgent               string   `json:"userAgent"`
	Referer                 string   `json:"referer"`
	Cookie                  string   `json:"cookie"`
	RequestTimeoutMillis    int      `json:"requestTimeoutMillis"`
	BodyLimit               int      `json:"bodyLimit"`
}

var safeLabel = regexp.MustCompile(`^[A-Za-z0-9][A-Za-z0-9_-]{0,79}$`)
var shortCode = regexp.MustCompile(`^[A-Za-z0-9]{9}$`)

func loadConfig(path string) (Config, error) {
	f, err := os.Open(path)
	if err != nil {
		return Config{}, errors.New("CONFIG_OPEN_FAILED")
	}
	defer f.Close()
	info, err := f.Stat()
	if err != nil || !info.Mode().IsRegular() || info.Size() > 65536 {
		return Config{}, errors.New("CONFIG_FILE_INVALID")
	}
	var c Config
	d := json.NewDecoder(io.LimitReader(f, 65537))
	d.DisallowUnknownFields()
	if d.Decode(&c) != nil {
		return c, errors.New("CONFIG_JSON_INVALID")
	}
	var extra any
	if d.Decode(&extra) != io.EOF {
		return c, errors.New("CONFIG_TRAILING_DATA")
	}
	if c.RequestTimeoutMillis == 0 {
		c.RequestTimeoutMillis = 10000
	}
	if c.BodyLimit == 0 {
		c.BodyLimit = 1024
	}
	if err := c.validate(); err != nil {
		return c, err
	}
	return c, nil
}

func (c Config) validate() error {
	bad := func(code string) error { return errors.New(code) }
	if c.Rate < 1 || c.Rate > 20000 || c.DurationMillis < 1 || c.DurationMillis > 90000 ||
		(c.WarmupMillis != 0 && c.WarmupMillis != 30000) || (c.Workers != 512 && c.Workers != 1024 && c.Workers != 2048 && c.Workers != 4096) ||
		c.WarmupMillis+c.DurationMillis > 120000 {
		return bad("CONFIG_WORKLOAD_BOUNDS")
	}
	if c.WarmupMillis > 0 && (c.WarmupStartRate < 1 || c.WarmupStartRate > c.Rate) {
		return bad("CONFIG_WARMUP_RATE")
	}
	if c.RequestTimeoutMillis < 1 || c.RequestTimeoutMillis > 10000 || c.BodyLimit < 1 || c.BodyLimit > 1024 {
		return bad("CONFIG_HTTP_BOUNDS")
	}
	if len(c.Sources) != 64 || len(c.Links) != 10 {
		return bad("CONFIG_FIXTURE_CARDINALITY")
	}
	for i, s := range c.Sources {
		if s != fmt.Sprintf("127.0.0.%d", i+2) {
			return bad("CONFIG_SOURCE_SEQUENCE")
		}
	}
	seen := make(map[string]bool)
	for _, l := range c.Links {
		u, err := url.Parse(l.OriginURL)
		if !shortCode.MatchString(l.ShortURI) || seen[l.ShortURI] || len(l.OriginURL) > 8192 ||
			err != nil || (u.Scheme != "https" && u.Scheme != "http") || u.Hostname() == "" || u.User != nil ||
			strings.ContainsAny(l.OriginURL, "\r\n\x00") {
			return bad("CONFIG_LINK_INVALID")
		}
		seen[l.ShortURI] = true
	}
	u, err := url.Parse(c.BaseURL)
	if err != nil || u.Scheme != "http" || u.Hostname() != "127.0.0.1" || u.Port() != "9080" ||
		u.User != nil || u.RawQuery != "" || u.Fragment != "" || (u.Path != "" && u.Path != "/") {
		return bad("CONFIG_BASE_REQUIRES_NETNS_EDGE_LOOPBACK")
	}
	if c.Host == "" || len(c.Host) > 253 || strings.ContainsAny(c.Host, "\r\n\t /\\@\x00") {
		return bad("CONFIG_HOST_INVALID")
	}
	if c.UserAgent == "" || len(c.UserAgent) > 128 || strings.ContainsAny(c.UserAgent, "\r\n\x00") ||
		c.Referer != "https://shortlink-perf.local/source" ||
		c.Cookie != "sl_uv=0123456789abcdef0123456789abcdef" {
		return bad("CONFIG_FIXED_HEADERS_INVALID")
	}
	if c.Output == "" || (c.RunID != "" && !safeLabel.MatchString(c.RunID)) ||
		(c.Label != "" && !safeLabel.MatchString(c.Label)) {
		return bad("CONFIG_OUTPUT_OR_LABEL_INVALID")
	}
	if newPlan(c).total > maxSamples {
		return bad("CONFIG_SAMPLE_LIMIT")
	}
	if c.RequestsDrainedReceipt != "" && (c.RunID == "" || c.Label == "" ||
		!filepath.IsAbs(c.RequestsDrainedReceipt) || filepath.Clean(c.RequestsDrainedReceipt) == filepath.Clean(c.Output)) {
		return bad("CONFIG_DRAIN_RECEIPT_INVALID")
	}
	return nil
}

// Build once per hot link, using the standard request encoder to validate wire syntax.
// No Transport, cookie jar, proxy discovery, redirect handler or retry loop is involved.
func requestBytes(c Config) ([][]byte, string, error) {
	u, err := url.Parse(c.BaseURL)
	if err != nil {
		return nil, "", errors.New("REQUEST_BASE_INVALID")
	}
	address := u.Host
	if u.Port() == "" {
		address = net.JoinHostPort(u.Hostname(), "80")
	}
	wire := make([][]byte, len(c.Links))
	for i, l := range c.Links {
		r, err := http.NewRequest(http.MethodGet, strings.TrimSuffix(c.BaseURL, "/")+"/"+l.ShortURI, nil)
		if err != nil {
			return nil, "", errors.New("REQUEST_BUILD_FAILED")
		}
		r.Host = c.Host
		r.Header.Set("User-Agent", c.UserAgent)
		r.Header.Set("Referer", c.Referer)
		r.Header.Set("Cookie", c.Cookie)
		r.Header.Set("Accept", "application/json")
		r.Header.Set("Content-Type", "application/json")
		r.Header.Set("X-Forwarded-Proto", "http")
		var b bytes.Buffer
		if r.Write(&b) != nil {
			return nil, "", errors.New("REQUEST_ENCODING_FAILED")
		}
		wire[i] = append([]byte(nil), b.Bytes()...)
	}
	return wire, address, nil
}

func timeout(c Config) time.Duration { return time.Duration(c.RequestTimeoutMillis) * time.Millisecond }
