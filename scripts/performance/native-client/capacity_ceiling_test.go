package main

import (
	"encoding/json"
	"os"
	"path/filepath"
	"reflect"
	"testing"
	"time"
)

func TestCapacityCeilingExplicitRateBounds(t *testing.T) {
	for _, rate := range []int{1, 10000, 10001, 20000} {
		c := validConfig()
		c.Rate, c.DurationMillis = rate, 1000
		if err := c.validate(); err != nil {
			t.Fatalf("explicit rate %d rejected: %v", rate, err)
		}
		if got := newPlan(c).total; got != rate {
			t.Fatalf("rate %d planned %d arrivals for one second", rate, got)
		}
	}
	for _, rate := range []int{-1, 0, 20001} {
		c := validConfig()
		c.Rate, c.DurationMillis = rate, 1000
		if err := c.validate(); err == nil || err.Error() != "CONFIG_WORKLOAD_BOUNDS" {
			t.Fatalf("invalid rate %d: got %v", rate, err)
		}
	}
	c := validConfig()
	c.Rate, c.DurationMillis = 20000, 1000
	p := newPlan(c)
	if p.at(1)-p.at(0) != 50*time.Microsecond || p.at(p.total-1) >= p.end() {
		t.Fatal("20k rate changed original arrival-plan timing or final boundary")
	}
}

func TestCapacityCeilingRetainsTotalSampleBudgetIncludingWarmup(t *testing.T) {
	if maxSamples != 1200000 {
		t.Fatal("aggregate sample budget changed")
	}
	for _, tc := range []struct {
		name       string
		warmMillis int
		startRate  int
		duration   int
		wantTotal  int
		wantError  string
	}{
		{"exact_no_warmup", 0, 0, 60000, 1200000, ""},
		{"over_no_warmup", 0, 0, 60001, 1200020, "CONFIG_SAMPLE_LIMIT"},
		{"duration_valid_but_sample_budget_exceeded", 0, 0, 90000, 1800000, "CONFIG_SAMPLE_LIMIT"},
		{"exact_constant_warmup", 30000, 20000, 30000, 1200000, ""},
		{"over_constant_warmup", 30000, 20000, 30001, 1200020, "CONFIG_SAMPLE_LIMIT"},
		{"linear_warmup_is_included", 30000, 100, 45000, 1201500, "CONFIG_SAMPLE_LIMIT"},
		{"linear_warmup_full_diagnostics_example", 30000, 100, 30000, 901500, ""},
	} {
		t.Run(tc.name, func(t *testing.T) {
			c := validConfig()
			c.Rate, c.DurationMillis = 20000, tc.duration
			c.WarmupMillis, c.WarmupStartRate = tc.warmMillis, tc.startRate
			if got := newPlan(c).total; got != tc.wantTotal {
				t.Fatalf("planned arrivals=%d want=%d", got, tc.wantTotal)
			}
			err := c.validate()
			if tc.wantError == "" {
				if err != nil {
					t.Fatal(err)
				}
			} else if err == nil || err.Error() != tc.wantError {
				t.Fatalf("expected %s, got %v", tc.wantError, err)
			}
		})
	}
}

func TestCapacityCeilingRetainsDiagnosticsPrefixLimit(t *testing.T) {
	if maxDiagnosticArrivals != 1000000 {
		t.Fatal("detailed diagnostics budget changed")
	}
	for _, tc := range []struct{ total, want int }{
		{0, 0}, {901500, 901500}, {1000000, 1000000}, {1000001, 1000000}, {1200000, 1000000},
	} {
		if got := diagnosticPrefixSize(tc.total); got != tc.want {
			t.Fatalf("diagnostic prefix for %d=%d want=%d", tc.total, got, tc.want)
		}
	}
}

func TestCapacityCeilingKeepsHTTPDefaultsWireAndFDPreparationOptIn(t *testing.T) {
	c := validConfig()
	c.Rate, c.DurationMillis = 20000, 1000
	c.RequestTimeoutMillis, c.BodyLimit = 0, 0
	raw, err := json.Marshal(c)
	if err != nil {
		t.Fatal(err)
	}
	path := filepath.Join(t.TempDir(), "ceiling-config.json")
	if err := os.WriteFile(path, raw, 0600); err != nil {
		t.Fatal(err)
	}
	loaded, err := loadConfig(path)
	if err != nil {
		t.Fatal(err)
	}
	if loaded.Rate != 20000 || loaded.RequestTimeoutMillis != 10000 || loaded.BodyLimit != 1024 || loaded.PreallocateFDTable {
		t.Fatal("explicit rate changed existing HTTP defaults or enabled FD preparation")
	}
	legacy := loaded
	legacy.Rate = 10000
	oldWire, oldAddress, oldErr := requestBytes(legacy)
	newWire, newAddress, newErr := requestBytes(loaded)
	if oldErr != nil || newErr != nil || oldAddress != newAddress || !reflect.DeepEqual(oldWire, newWire) {
		t.Fatal("raising configured rate changed HTTP bytes or endpoint")
	}
	loaded.RequestTimeoutMillis = 10001
	if err := loaded.validate(); err == nil || err.Error() != "CONFIG_HTTP_BOUNDS" {
		t.Fatalf("HTTP timeout upper bound changed: %v", err)
	}
}
