package com.streamflix.reco.dto;

/** A recommended video id with its blended score and the reason it was surfaced. */
public record RecItem(long videoId, double score, String reason) {
}
