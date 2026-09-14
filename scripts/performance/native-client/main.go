package main

import (
	"context"
	"encoding/json"
	"flag"
	"fmt"
	"os"
	"os/signal"
	"syscall"
)

func main() { os.Exit(command(os.Args[1:])) }
func command(args []string) int {
	flags := flag.NewFlagSet("native-client", flag.ContinueOnError)
	path := flags.String("config", "", "Private JSON configuration; output is exclusively created")
	showVersion := flags.Bool("version", false, "Print tool kind/version without reading configuration")
	if flags.Parse(args) != nil {
		return 64
	}
	if *showVersion {
		fmt.Println(kind + " " + version)
		return 0
	}
	if *path == "" || flags.NArg() != 0 {
		fmt.Fprintln(os.Stderr, "CONFIG_ARGUMENT_REQUIRED")
		return 64
	}
	c, err := loadConfig(*path)
	if err != nil {
		fmt.Fprintln(os.Stderr, err.Error())
		return 64
	}
	// Reserve the public output before any network operation. Existing results
	// are never overwritten, including a prior partial/crashed attempt.
	out, err := os.OpenFile(c.Output, os.O_WRONLY|os.O_CREATE|os.O_EXCL, 0644)
	if err != nil {
		fmt.Fprintln(os.Stderr, "OUTPUT_EXCLUSIVE_CREATE_FAILED")
		return 64
	}
	defer out.Close()
	ctx, cancel := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer cancel()
	result, err := runWorkload(ctx, c)
	if err != nil {
		if result.Preparation != nil {
			// Preserve failure evidence in the already reserved output. No worker,
			// measurement window, socket, or HTTP request has been started.
			failure := struct {
				Kind               string         `json:"kind"`
				Version            string         `json:"version"`
				RunID              string         `json:"runId"`
				MeasurementStarted bool           `json:"measurementStarted"`
				Preparation        *FDPreparation `json:"preparation"`
			}{"NATIVE_GO_PREPARATION_FAILED", version, c.RunID, false, result.Preparation}
			if json.NewEncoder(out).Encode(failure) != nil || out.Sync() != nil {
				fmt.Fprintln(os.Stderr, "PREPARATION_FAILURE_OUTPUT_WRITE_FAILED")
				return 74
			}
			fmt.Fprintln(os.Stderr, err.Error())
			return 70
		}
		fmt.Fprintln(os.Stderr, "PREPARATION_FAILED")
		return 70
	}
	enc := json.NewEncoder(out)
	enc.SetIndent("", "  ")
	if enc.Encode(result) != nil || out.Sync() != nil {
		fmt.Fprintln(os.Stderr, "OUTPUT_WRITE_FAILED")
		return 74
	}
	fmt.Printf("%s finished: sent=%d completed=%d correct=%d dropped=%d reason=%s\n", kind, result.All.Sent, result.All.Completed, result.All.Correct, result.All.Dropped, result.Stop.Reason)
	if result.RequestsDrainedReceiptError != "" {
		fmt.Fprintln(os.Stderr, result.RequestsDrainedReceiptError)
		return 74
	}
	if !result.MeasurementComplete || !result.Conservation || result.All.Errors > 0 || result.All.NotCompleted > 0 {
		return 2
	}
	// A complete run with dropped arrivals is still not a capacity PASS; the
	// independent wrapper must evaluate target coverage, drops, P99 and events.
	return 0
}
