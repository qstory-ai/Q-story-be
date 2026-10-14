package com.qstory.backend.institution;

/** 공공데이터 기관 목록의 종류 - 유치원(교육부 유치원알리미)과 어린이집(어린이집정보공개포털). */
public enum InstitutionKind {
    KINDERGARTEN("유치원"),
    DAYCARE("어린이집");

    private final String label;

    InstitutionKind(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
