package com.qa.kb.model;

public record SpaceRow(
    Long id, String name, String slug, String icon, String description, Integer sort) {}
