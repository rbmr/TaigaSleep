package ratelimit

import (
	"testing"
	"time"
)

// TestAllowSpendsOneFixedWindowPerAccount checks the request budget: it is spent one
// request at a time, answers the time left in the window, and resets when the window
// is over. Two accounts and two budgets never share a counter.
func TestAllowSpendsOneFixedWindowPerAccount(t *testing.T) {
	limits := NewLimits()
	budget := Budget{Name: "test", MaxRequests: 2, Window: 10 * time.Second}
	other := Budget{Name: "other", MaxRequests: 2, Window: 10 * time.Second}
	start := time.Unix(1_800_000_000, 0)

	for i := 0; i < budget.MaxRequests; i++ {
		if allowed, wait := limits.Allow(budget, "alice", start); !allowed {
			t.Fatalf("request %d refused after %v", i+1, wait)
		}
	}
	allowed, wait := limits.Allow(budget, "alice", start.Add(4*time.Second))
	if allowed {
		t.Fatal("the budget allowed a request past its maximum")
	}
	if wait != 6*time.Second {
		t.Errorf("wait = %v, want the 6 s left in the window", wait)
	}

	if allowed, wait := limits.Allow(budget, "bob", start.Add(4*time.Second)); !allowed {
		t.Errorf("another account was refused after %v", wait)
	}
	if allowed, wait := limits.Allow(other, "alice", start.Add(4*time.Second)); !allowed {
		t.Errorf("another budget was refused after %v", wait)
	}

	if allowed, wait := limits.Allow(budget, "alice", start.Add(10*time.Second)); !allowed {
		t.Errorf("the next window refused a request after %v", wait)
	}
}

// TestConnectCountsOpenConnectionsPerAccount checks the realtime budget: one account
// holds at most the maximum, another account is unaffected, and a released place is
// free again. Releasing twice must not free two places.
func TestConnectCountsOpenConnectionsPerAccount(t *testing.T) {
	limits := NewLimits()

	releases := make([]func(), 0, AccountRealtimeMaxConnections)
	for i := 0; i < AccountRealtimeMaxConnections; i++ {
		release, ok := limits.Connect("alice")
		if !ok {
			t.Fatalf("connection %d refused", i+1)
		}
		releases = append(releases, release)
	}
	if _, ok := limits.Connect("alice"); ok {
		t.Fatal("the account opened more connections than its maximum")
	}
	if _, ok := limits.Connect("bob"); !ok {
		t.Error("another account was refused while alice held the maximum")
	}

	releases[0]()
	releases[0]()
	release, ok := limits.Connect("alice")
	if !ok {
		t.Fatal("a released connection was not free again")
	}
	release()
}

// TestSweepDropsFinishedWindows keeps the counters from growing with every account
// that ever made a request.
func TestSweepDropsFinishedWindows(t *testing.T) {
	limits := NewLimits()
	budget := APIBudget()
	start := time.Unix(1_800_000_000, 0)

	limits.Allow(budget, "alice", start)
	if len(limits.windows) != 1 {
		t.Fatalf("windows = %d, want 1", len(limits.windows))
	}
	// The next call is long after the window ended, which also sweeps.
	limits.Allow(budget, "bob", start.Add(11*time.Minute))
	if _, ok := limits.windows[budget.Name+"|alice"]; ok {
		t.Error("a finished window was kept")
	}
}
