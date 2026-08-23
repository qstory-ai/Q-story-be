package com.qstory.backend.common.enums;

/**
 * What a story asset is, as a real enum rather than a naming convention on the id.
 *
 * <p>This used to be inferred from an id prefix ({@code HG-ART-}/{@code HG-AUD-}) and from which of
 * assets.json's four parallel maps an entry happened to live in, which is why the same illustration
 * could be spelled five different ways across the content files. The category is now a column, and
 * the slug carries no type information at all.
 */
public enum AssetCategory {
    /** A fixed illustration shown by a scene's visual segment. */
    SCENE_ART,
    /** The illustration for one action family's branch, shown while the child chooses. */
    BRANCH_ART,
    /** Pre-rendered narration for one fixed utterance. */
    NARRATION,
    /** The short acknowledgement clip played while routing into a branch. */
    BRIDGE,
}
