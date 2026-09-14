package main

// States are fixed-size counters in the hot path. ID bytes are copied only after
// validation, so neither invalid input nor the response header backing buffer is
// retained. Exact wire spelling is preserved for joining the gateway access log.
type gatewayRequestIDState uint8

const (
	gatewayIDNotReceived gatewayRequestIDState = iota
	gatewayIDHex32
	gatewayIDUUID
	gatewayIDMissing
	gatewayIDDuplicate
	gatewayIDInvalidLength
	gatewayIDInvalidFormat
)

func (s gatewayRequestIDState) String() string {
	switch s {
	case gatewayIDNotReceived:
		return "NOT_RECEIVED"
	case gatewayIDHex32:
		return "VALID_HEX32"
	case gatewayIDUUID:
		return "VALID_UUID"
	case gatewayIDMissing:
		return "MISSING"
	case gatewayIDDuplicate:
		return "DUPLICATE"
	case gatewayIDInvalidLength:
		return "INVALID_LENGTH"
	case gatewayIDInvalidFormat:
		return "INVALID_FORMAT"
	default:
		return "UNKNOWN_STATE"
	}
}

type gatewayRequestID struct {
	bytes  [36]byte
	length uint8
	state  gatewayRequestIDState
}

func (id gatewayRequestID) valid() bool {
	return id.state == gatewayIDHex32 || id.state == gatewayIDUUID
}
func (id gatewayRequestID) value() string {
	if !id.valid() {
		return ""
	}
	return string(id.bytes[:id.length])
}

func asciiHex(b byte) bool {
	return b >= '0' && b <= '9' || b >= 'a' && b <= 'f' || b >= 'A' && b <= 'F'
}

func parseGatewayRequestID(values []string) gatewayRequestID {
	var out gatewayRequestID
	if len(values) == 0 {
		out.state = gatewayIDMissing
		return out
	}
	if len(values) != 1 {
		out.state = gatewayIDDuplicate
		return out
	}
	v := values[0]
	if len(v) != 32 && len(v) != 36 {
		out.state = gatewayIDInvalidLength
		return out
	}
	for i := 0; i < len(v); i++ {
		if len(v) == 36 && (i == 8 || i == 13 || i == 18 || i == 23) {
			if v[i] != '-' {
				out.state = gatewayIDInvalidFormat
				return out
			}
		} else if !asciiHex(v[i]) {
			out.state = gatewayIDInvalidFormat
			return out
		}
	}
	out.length = uint8(len(v))
	out.state = gatewayIDHex32
	if len(v) == 36 {
		out.state = gatewayIDUUID
	}
	copy(out.bytes[:], v)
	return out
}

// Counts cover every completed exchange, including those outside the diagnostic
// prefix. Unsent arrivals have no response and never receive a fabricated ID.
type GatewayRequestIDSummary struct {
	Required           bool `json:"required"`
	CompletedExchanges int  `json:"completedExchanges"`
	ValidHex32         int  `json:"validHex32"`
	ValidUUID          int  `json:"validUUID"`
	Missing            int  `json:"missing"`
	Duplicate          int  `json:"duplicate"`
	InvalidLength      int  `json:"invalidLength"`
	InvalidFormat      int  `json:"invalidFormat"`
	NotReceived        int  `json:"notReceived"`
	UnknownState       int  `json:"unknownState"`
	AccountingPassed   bool `json:"accountingPassed"`
	RequiredPassed     bool `json:"requiredPassed"`
}

func (s *GatewayRequestIDSummary) observe(state gatewayRequestIDState) {
	s.CompletedExchanges++
	switch state {
	case gatewayIDHex32:
		s.ValidHex32++
	case gatewayIDUUID:
		s.ValidUUID++
	case gatewayIDMissing:
		s.Missing++
	case gatewayIDDuplicate:
		s.Duplicate++
	case gatewayIDInvalidLength:
		s.InvalidLength++
	case gatewayIDInvalidFormat:
		s.InvalidFormat++
	case gatewayIDNotReceived:
		s.NotReceived++
	default:
		s.UnknownState++
	}
}

func (s *GatewayRequestIDSummary) finish(completed int) {
	s.AccountingPassed = s.CompletedExchanges == completed && s.UnknownState == 0 &&
		s.CompletedExchanges == s.ValidHex32+s.ValidUUID+s.Missing+s.Duplicate+s.InvalidLength+s.InvalidFormat+s.NotReceived
	s.RequiredPassed = s.AccountingPassed && (!s.Required || s.ValidHex32+s.ValidUUID == completed)
}
