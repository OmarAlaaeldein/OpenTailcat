package engine

import (
	"errors"
	"fmt"
	"log"
)

// recoverExported turns a panic in a gomobile-exported function into an
// error returned through err (or only a log line when err is nil). An
// unrecovered Go panic aborts the whole Android app process.
func recoverExported(name string, err *error) {
	if r := recover(); r != nil {
		e := exportedPanicError(name, r)
		if err != nil {
			*err = e
		}
	}
}

// exportedPanicError logs a recovered panic and returns it as an error.
// Call it only from a deferred function that recovered r.
func exportedPanicError(name string, r any) error {
	msg := fmt.Sprintf("%s panic: %v @ %s", name, r, panicSite())
	log.Printf("Tailcat %s", msg)
	return errors.New(msg)
}
