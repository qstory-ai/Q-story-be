package com.qstory.backend.story.entity;

/** personas.yaml의 relationships[] 항목 - {@code with}는 같은 스토리의 다른 cast tag. */
public record PersonaRelationship(String with, String label) {}
