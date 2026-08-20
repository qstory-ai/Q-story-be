package com.qstory.backend.provider.openrouter;

public record RouteOption(String id, String label, String meaning, String actionFamilyId) {

    public RouteOption withCopy(String label, String meaning) {
        return new RouteOption(id, label, meaning, actionFamilyId);
    }
}
