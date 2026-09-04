package engine

import "testing"

type testProtector struct {
	fds []int
}

func (t *testProtector) Protect(fd int) bool {
	t.fds = append(t.fds, fd)
	return true
}

func TestProtectFDNilIsNoop(t *testing.T) {
	SetSocketProtector(nil)
	if err := protectFD(3); err != nil {
		t.Fatalf("nil protector: %v", err)
	}
}

func TestProtectFDInvokesHook(t *testing.T) {
	p := &testProtector{}
	SetSocketProtector(p)
	t.Cleanup(func() { SetSocketProtector(nil) })
	if err := protectFD(7); err != nil {
		t.Fatal(err)
	}
	if len(p.fds) != 1 || p.fds[0] != 7 {
		t.Fatalf("got %v", p.fds)
	}
}
