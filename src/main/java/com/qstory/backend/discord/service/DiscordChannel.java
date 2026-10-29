package com.qstory.backend.discord.service;

/** 알림을 보내는 디스코드 채널 - 채널마다 웹훅이 하나다. */
public enum DiscordChannel {
    /** #지표 - 아침 지표 요약. */
    METRICS,
    /** #배포 - 배포·빌드 결과(백엔드에서는 연결 테스트만, 실제 결과는 GitHub Actions가 보낸다). */
    DEPLOY,
    /** #소식 - 사용자 반응 묶음, 협업 문의. */
    NEWS,
    /** #경보 - 한도·비용 경고, 봇 정시 실행 실패. 웹훅을 따로 두지 않았으면 #소식으로 간다. */
    ALERTS
}
