package com.qstory.backend.story;

import java.util.List;

public record ActionFamily(
        String id,
        String meaning,
        String acknowledgementText,
        String reportSummary,
        String bridgeAudioId,
        String branchAssetId,
        List<String> requiresPriorFamilyIds) {

    public ActionFamily {
        requiresPriorFamilyIds = requiresPriorFamilyIds == null ? List.of() : requiresPriorFamilyIds;
    }
}
