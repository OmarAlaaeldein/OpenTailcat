-------------------------- MODULE NetworkCallback --------------------------
EXTENDS Naturals
CONSTANT SplitLoss
VARIABLES ui, live, callback, snapshot, stopped
vars == <<ui, live, callback, snapshot, stopped>>
Init == /\ ui = "CONNECTED" /\ live = TRUE /\ callback = "ready"
        /\ snapshot = "none" /\ stopped = FALSE

\* TunnelController's NONE branch: compareAndSet is one atomic transition.
\* SplitLoss restores its former unsynchronized read followed by a write.
Loss == /\ callback = "ready"
        /\ IF SplitLoss
           THEN /\ snapshot' = ui /\ callback' = "write" /\ UNCHANGED ui
           ELSE /\ ui' = IF ui = "CONNECTED" THEN "RECONNECTING" ELSE ui
                /\ callback' = "done" /\ UNCHANGED snapshot
        /\ UNCHANGED <<live, stopped>>
LossWrite == /\ callback = "write"
             /\ ui' = IF snapshot = "CONNECTED" THEN "RECONNECTING" ELSE ui
             /\ callback' = "done" /\ UNCHANGED <<live, snapshot, stopped>>
Stop == /\ ~stopped /\ stopped' = TRUE /\ live' = FALSE /\ ui' = "DISCONNECTED"
        /\ UNCHANGED <<callback, snapshot>>
Next == Loss \/ LossWrite \/ Stop
        \/ (callback = "done" /\ stopped /\ UNCHANGED vars)
NoResurrection == ~live => ui = "DISCONNECTED"
TypeOK == /\ ui \in {"CONNECTED", "RECONNECTING", "DISCONNECTED"}
          /\ callback \in {"ready", "write", "done"}
          /\ snapshot \in {"none", "CONNECTED", "RECONNECTING", "DISCONNECTED"}
          /\ live \in BOOLEAN /\ stopped \in BOOLEAN
=============================================================================
