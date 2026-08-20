package com.qstory.backend.story;

import com.qstory.backend.story.dto.StoryCatalogEntry;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Public story catalog - metadata only, not in-play content (see StoryCatalogEntry). */
@Tag(name = "Stories", description = "Story catalog metadata")
@RestController
@RequestMapping("/v1/stories")
public class StoryController {

    private final StoryCatalogService catalogService;

    public StoryController(StoryCatalogService catalogService) {
        this.catalogService = catalogService;
    }

    @Operation(summary = "List available stories")
    @GetMapping
    public List<StoryCatalogEntry> list() {
        return catalogService.list();
    }

    @Operation(summary = "Get a single story by its id")
    @GetMapping("/{storyId}")
    public StoryCatalogEntry get(@PathVariable String storyId) {
        return catalogService.get(storyId);
    }
}
