package engine

import (
	"math"
	"testing"
	"time"
)

type recordedProgress struct {
	mbps, fraction float64
}

type progressRecorder struct {
	calls []recordedProgress
}

func (r *progressRecorder) OnProgress(mbps, fraction float64) {
	r.calls = append(r.calls, recordedProgress{mbps, fraction})
}

func TestSpeedMeterReportsRunningRate(t *testing.T) {
	rec := &progressRecorder{}
	m := newSpeedMeter(rec, 4*time.Second)

	m.report(1 << 20)
	if len(rec.calls) != 0 {
		t.Fatalf("reported during the warm-up: %+v", rec.calls)
	}

	// One second in: 1,000,000 bytes is 8 Mbps and a quarter of the window.
	m.start = time.Now().Add(-time.Second)
	m.report(1_000_000)
	if len(rec.calls) != 1 {
		t.Fatalf("got %d reports after the warm-up, want 1", len(rec.calls))
	}
	got := rec.calls[0]
	if math.Abs(got.mbps-8) > 0.1 || math.Abs(got.fraction-0.25) > 0.01 {
		t.Fatalf("report = %+v, want about 8 Mbps at 0.25", got)
	}

	m.report(2_000_000)
	if len(rec.calls) != 1 {
		t.Fatal("reported again within the progress interval")
	}

	m.start = time.Now().Add(-10 * time.Second)
	m.last = time.Time{}
	m.report(10_000_000)
	if len(rec.calls) != 2 || rec.calls[1].fraction != 1 {
		t.Fatalf("a transfer past its window must report fraction 1: %+v", rec.calls)
	}
}

func TestSpeedMeterWithoutCallback(t *testing.T) {
	m := newSpeedMeter(nil, time.Second)
	m.start = time.Now().Add(-time.Second)
	m.report(1000) // must not panic
}
