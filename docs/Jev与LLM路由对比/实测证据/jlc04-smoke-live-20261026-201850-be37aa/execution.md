# Route benchmark execution

mode=LIVE; stage=smoke; live explicitly enabled.

No automatic recovery; STOP file requests stop before the next request. Forced process termination may leave STARTED progress with no final record; never reissue it under this runId.

Completed=60/60; status=COMPLETE; stopReason=null; finished=2026-10-26T12:19:55.805821800Z
