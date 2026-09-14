//go:build linux && amd64

package main

import (
	"encoding/json"
	"os"
	"path/filepath"
	"reflect"
	"testing"
)

func TestFDPreparationConfigIsOptInAndKeepsArrivalAndWire(t *testing.T) {
	baseline := validConfig()
	if baseline.PreallocateFDTable {
		t.Fatal("preparation must remain opt-in")
	}
	prepared := baseline
	prepared.PreallocateFDTable = true
	if !reflect.DeepEqual(newPlan(baseline), newPlan(prepared)) {
		t.Fatal("preparation changed arrival plan")
	}
	oldWire, oldAddress, oldError := requestBytes(baseline)
	newWire, newAddress, newError := requestBytes(prepared)
	if oldError != nil || newError != nil || oldAddress != newAddress || !reflect.DeepEqual(oldWire, newWire) {
		t.Fatal("preparation changed HTTP request bytes or destination")
	}
	for _, enabled := range []bool{false, true} {
		config := baseline
		config.PreallocateFDTable = enabled
		raw, err := json.Marshal(config)
		if err != nil {
			t.Fatal(err)
		}
		path := filepath.Join(t.TempDir(), "config.json")
		if err := os.WriteFile(path, raw, 0600); err != nil {
			t.Fatal(err)
		}
		loaded, err := loadConfig(path)
		if err != nil || loaded.PreallocateFDTable != enabled {
			t.Fatalf("explicit config roundtrip: enabled=%v err=%v", enabled, err)
		}
	}
	metadata, err := maybePrepareFDTable(false)
	if metadata != nil || err != nil {
		t.Fatal("disabled path should neither prepare nor emit metadata")
	}
}

func TestFDPreparationStatusParserRejectsAmbiguity(t *testing.T) {
	for _, text := range []string{"", "FDSize: nope", "FDSize: -1", "FDSize: 0", "FDSize: 64 extra", "FDSize: 64\nFDSize: 128"} {
		if _, err := fdSizeFromStatus(text); err == nil {
			t.Fatalf("accepted invalid FDSize %q", text)
		}
	}
	got, err := fdSizeFromStatus("Name:\ttest\nFDSize:\t1024\nVmSize:\t8 kB\n")
	if err != nil || got != 1024 {
		t.Fatal("valid proc status rejected")
	}
}
