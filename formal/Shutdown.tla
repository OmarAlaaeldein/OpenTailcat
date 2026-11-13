----------------------------- MODULE Shutdown -----------------------------
EXTENDS Naturals, FiniteSets

\* Focused progress model: one in-flight start and one Disconnect, no retry
\* or younger start. The full Lifecycle model checks those interleavings.
\* Stop can return before background close finishes (its timeout), and a
\* JNI Prepare already past Kotlin's last check can begin AFTER shutdown.
CONSTANT Mutant
ASSUME Mutant \in {"none", "skip-abandon-stop", "leak-local-dup", "forget-close", "stuck-stop"}

Callers == {"job", "shutdown"}
VARIABLES job, shutdown, stops, stopping, owner, session, cancelled,
          prepared, androidFd, bridgeFd, published, closePending, stale,
          uiLive, heartbeat
vars == <<job, shutdown, stops, stopping, owner, session, cancelled,
          prepared, androidFd, bridgeFd, published, closePending, stale,
          uiLive, heartbeat>>

Init == /\ job = "check" /\ shutdown = "idle"
        /\ stops = [c \in Callers |-> "idle"]
        /\ stopping = FALSE /\ owner = "none" /\ session = FALSE /\ cancelled = FALSE
        /\ prepared = FALSE /\ androidFd = FALSE /\ bridgeFd = FALSE /\ published = FALSE
        /\ closePending = FALSE /\ stale = FALSE /\ uiLive = FALSE /\ heartbeat = 0

Disconnect == /\ ~stale /\ stale' = TRUE /\ shutdown' = "close-tun"
              /\ UNCHANGED <<job, stops, stopping, owner, session, cancelled,
                   prepared, androidFd, bridgeFd, published, closePending, uiLive, heartbeat>>

\* Each branch is one Kotlin lock region or one native call stage. In
\* particular duplicate creation and publication are separate mu regions.
Start ==
  /\ CASE job = "check" ->
           /\ job' = IF stale THEN "cleanup" ELSE "prepare"
           /\ UNCHANGED <<session, cancelled, prepared, androidFd, bridgeFd, published, closePending, uiLive, stops>>
       [] job = "prepare" ->
           /\ ~stopping
           /\ session' = TRUE /\ cancelled' = FALSE /\ prepared' = FALSE
           /\ job' = "handshake"
           /\ UNCHANGED <<androidFd, bridgeFd, published, closePending, uiLive, stops>>
       [] job = "handshake" ->
           /\ prepared' = (session /\ ~cancelled)
           /\ job' = "after-prepare"
           /\ UNCHANGED <<session, cancelled, androidFd, bridgeFd, published, closePending, uiLive, stops>>
       [] job = "after-prepare" ->
           /\ job' = IF stale \/ ~prepared THEN "cleanup" ELSE "establish"
           /\ UNCHANGED <<session, cancelled, prepared, androidFd, bridgeFd, published, closePending, uiLive, stops>>
       [] job = "establish" ->
           /\ androidFd' = ~stale
           /\ job' = IF stale THEN "cleanup" ELSE "attach-check"
           /\ UNCHANGED <<session, cancelled, prepared, bridgeFd, published, closePending, uiLive, stops>>
       [] job = "attach-check" ->
           /\ job' = IF stale \/ cancelled \/ ~session THEN "cleanup" ELSE "dup"
           /\ UNCHANGED <<session, cancelled, prepared, androidFd, bridgeFd, published, closePending, uiLive, stops>>
       [] job = "dup" ->
           /\ bridgeFd' = TRUE /\ job' = "publish-bridge"
           /\ UNCHANGED <<session, cancelled, prepared, androidFd, published, closePending, uiLive, stops>>
       [] job = "publish-bridge" ->
           /\ IF session /\ ~cancelled
              THEN /\ published' = TRUE /\ job' = "attach-return"
                   /\ UNCHANGED <<bridgeFd, closePending>>
              ELSE /\ published' = FALSE /\ job' = "cleanup"
                   /\ bridgeFd' = (Mutant = "leak-local-dup")
                   /\ UNCHANGED closePending
           /\ UNCHANGED <<session, cancelled, prepared, androidFd, uiLive, stops>>
       [] job = "attach-return" ->
           /\ job' = IF cancelled \/ ~session THEN "cleanup" ELSE "publish-ui"
           /\ UNCHANGED <<session, cancelled, prepared, androidFd, bridgeFd, published, closePending, uiLive, stops>>
       [] job = "publish-ui" ->
           /\ uiLive' = ~stale
           /\ job' = IF stale THEN "cleanup" ELSE "done"
           /\ UNCHANGED <<session, cancelled, prepared, androidFd, bridgeFd, published, closePending, stops>>
       [] job = "cleanup" ->
           /\ androidFd' = FALSE
           /\ stops' = IF Mutant = "skip-abandon-stop" THEN stops
                        ELSE [stops EXCEPT !["job"] = "enter"]
           /\ job' = IF Mutant = "skip-abandon-stop" THEN "done" ELSE "wait-stop"
           /\ UNCHANGED <<session, cancelled, prepared, bridgeFd, published, closePending, uiLive>>
       [] job = "wait-stop" ->
           /\ stops["job"] = "done" /\ job' = "done"
           /\ UNCHANGED <<session, cancelled, prepared, androidFd, bridgeFd, published, closePending, uiLive, stops>>
       [] OTHER -> FALSE
  /\ UNCHANGED <<shutdown, stopping, owner, stale, heartbeat>>

ShutdownStep ==
  /\ CASE shutdown = "close-tun" ->
           /\ androidFd' = FALSE
           /\ stops' = [stops EXCEPT !["shutdown"] = "enter"]
           /\ shutdown' = "wait-stop" /\ UNCHANGED uiLive
       [] shutdown = "wait-stop" ->
           /\ stops["shutdown"] = "done"
           /\ uiLive' = FALSE /\ shutdown' = "done"
           /\ UNCHANGED <<androidFd, stops>>
       [] OTHER -> FALSE
  /\ UNCHANGED <<job, stopping, owner, session, cancelled, prepared,
                  bridgeFd, published, closePending, stale, heartbeat>>

StopEnter(c) ==
  /\ stops[c] = "enter"
  /\ IF stopping
     THEN /\ stops' = [stops EXCEPT ![c] = "wait"]
          /\ UNCHANGED <<stopping, owner, session, cancelled, prepared, closePending>>
     ELSE /\ stopping' = TRUE /\ owner' = c
          /\ session' = FALSE /\ cancelled' = TRUE /\ prepared' = FALSE
          /\ closePending' = (closePending \/ (session /\ published /\ bridgeFd /\ Mutant # "forget-close"))
          /\ stops' = [stops EXCEPT ![c] = "return"]
  /\ UNCHANGED <<job, shutdown, androidFd, bridgeFd, published, stale, uiLive, heartbeat>>

\* closeSession may finish in the background after Stop's bounded wait.
StopReturn(c) == /\ stops[c] = "return" /\ owner = c
                 /\ Mutant # "stuck-stop"
                 /\ stops' = [stops EXCEPT ![c] = "done"]
                 /\ stopping' = FALSE /\ owner' = "none"
                 /\ UNCHANGED <<job, shutdown, session, cancelled, prepared, androidFd,
                                 bridgeFd, published, closePending, stale, uiLive, heartbeat>>
StopWait(c) == /\ stops[c] = "wait" /\ ~stopping
               /\ stops' = [stops EXCEPT ![c] = "done"]
               /\ UNCHANGED <<job, shutdown, stopping, owner, session, cancelled, prepared,
                               androidFd, bridgeFd, published, closePending, stale, uiLive, heartbeat>>
DrainBridge == /\ closePending /\ bridgeFd
               /\ bridgeFd' = FALSE /\ closePending' = FALSE
               /\ UNCHANGED <<job, shutdown, stops, stopping, owner, session, cancelled,
                               prepared, androidFd, published, stale, uiLive, heartbeat>>

\* This actor keeps running even if cleanup is stuck. A deadlock check alone
\* therefore cannot make these mutants pass as termination guarantees.
Heartbeat == /\ heartbeat' = 1 - heartbeat
             /\ UNCHANGED <<job, shutdown, stops, stopping, owner, session, cancelled,
                             prepared, androidFd, bridgeFd, published, closePending, stale, uiLive>>
Next == Start \/ Disconnect \/ ShutdownStep \/ DrainBridge \/ Heartbeat
        \/ (\E c \in Callers : StopEnter(c) \/ StopReturn(c) \/ StopWait(c))
Spec == /\ Init /\ [][Next]_vars
        /\ WF_vars(Start) /\ WF_vars(Disconnect) /\ WF_vars(ShutdownStep)
        /\ WF_vars(DrainBridge)
        /\ \A c \in Callers : WF_vars(StopEnter(c)) /\ WF_vars(StopReturn(c)) /\ WF_vars(StopWait(c))

TypeOK == /\ job \in {"check", "prepare", "handshake", "after-prepare", "establish",
                      "attach-check", "dup", "publish-bridge", "attach-return", "publish-ui",
                      "cleanup", "wait-stop", "done"}
          /\ shutdown \in {"idle", "close-tun", "wait-stop", "done"}
          /\ stops \in [Callers -> {"idle", "enter", "return", "wait", "done"}]
          /\ owner \in Callers \cup {"none"}
          /\ <<stopping, session, cancelled, prepared, androidFd, bridgeFd,
                published, closePending, stale, uiLive>> \in [1..10 -> BOOLEAN]
          /\ heartbeat \in {0, 1}
LiveCleanup == stale ~> (~androidFd /\ ~bridgeFd /\ ~session /\ ~stopping
                        /\ ~uiLive /\ job = "done" /\ shutdown = "done")
LiveStopReturns == \A c \in Callers : (stops[c] \in {"enter", "wait", "return"} ~> stops[c] = "done")
LiveStartReturns == stale ~> (job = "done")
LiveDisconnectObserved == <>stale
=============================================================================
