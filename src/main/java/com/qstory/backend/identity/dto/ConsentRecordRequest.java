package com.qstory.backend.identity.dto;

import java.util.List;

/** POST /v1/me/consents - 가입 이후 온보딩·마이페이지에서 동의/철회를 기록한다. */
public record ConsentRecordRequest(String source, List<Item> items) {

    public record Item(String type, boolean agreed, String version) {}
}
