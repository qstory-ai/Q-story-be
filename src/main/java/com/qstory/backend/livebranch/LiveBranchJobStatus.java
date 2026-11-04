package com.qstory.backend.livebranch;

/** live_branch_job.status의 생명주기 - QUEUED/GENERATING을 지나 READY 또는 FAILED로 끝난다. */
public enum LiveBranchJobStatus {
    QUEUED,
    GENERATING,
    READY,
    FAILED
}
