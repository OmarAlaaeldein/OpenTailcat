package main

import (
	"flag"
	"fmt"
	"os"
	"os/signal"
	"syscall"
	"time"

	"com.tailcat.vpn/engine"
)

func main() {
	if len(os.Args) < 2 {
		printUsage()
		os.Exit(1)
	}

	command := os.Args[1]

	switch command {
	case "up", "connect":
		upCmd := flag.NewFlagSet("up", flag.ExitOnError)
		tokenFlag := upCmd.String("token", "", "Tailcat connection token (tc...)")
		upCmd.Parse(os.Args[2:])

		token := *tokenFlag
		if token == "" && len(upCmd.Args()) > 0 {
			token = upCmd.Args()[0]
		}

		if token == "" {
			fmt.Println("Error: Connection token required. Usage: tailcat-cli up <token>")
			os.Exit(1)
		}

		fmt.Println("Tailcat: initializing tunnel engine...")

		if err := engine.Prepare(token); err != nil {
			fmt.Printf("Failed to connect: %v\n", err)
			os.Exit(1)
		}

		fmt.Println("Tunnel engine reports active. Press Ctrl+C to terminate.")

		// Handle graceful shutdown on SIGINT/SIGTERM
		sigChan := make(chan os.Signal, 1)
		signal.Notify(sigChan, os.Interrupt, syscall.SIGTERM)

		ticker := time.NewTicker(2 * time.Second)
		defer ticker.Stop()

		for {
			select {
			case <-sigChan:
				fmt.Println("\nDisconnecting Tailcat tunnel...")
				_ = engine.Stop()
				fmt.Println("✓ Disconnected.")
				return
			case <-ticker.C:
				// Telemetry poll
			}
		}

	case "status":
		fmt.Println(engine.GetStatsJSON())

	case "down", "disconnect":
		_ = engine.Stop()
		fmt.Println("✓ Tailcat tunnel stopped.")

	default:
		printUsage()
		os.Exit(1)
	}
}

func printUsage() {
	fmt.Println("Tailcat VPN Client — Multiplatform CLI (Linux / macOS / Android)")
	fmt.Println("\nUsage:")
	fmt.Println("  tailcat-cli up <token>       Connect to gateway token")
	fmt.Println("  tailcat-cli status           Display active connection stats")
	fmt.Println("  tailcat-cli down             Disconnect active tunnel")
}
