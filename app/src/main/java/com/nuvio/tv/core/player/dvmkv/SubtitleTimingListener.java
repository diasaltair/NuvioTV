package com.nuvio.tv.core.player.dvmkv;

import androidx.annotation.Nullable;

/**
 * Receives the timing of every embedded subtitle sample the Matroska extractor reads, for
 * all text tracks regardless of which one the player has selected. Used to compare external
 * (addon) subtitles against the file's own cue timeline without decoding or rendering.
 *
 * <p>Called on the extractor loading thread; implementations must be cheap and must not block.
 */
public interface SubtitleTimingListener {

  /** Reports an embedded text track once its TrackEntry has been parsed. */
  void onSubtitleTrack(
      int trackNumber, String codecId, @Nullable String language, boolean forced);

  /**
   * Reports one subtitle sample.
   *
   * @param trackNumber Matroska track number (matches {@code Format.id}).
   * @param startUs Presentation start in microseconds.
   * @param durationUs Duration in microseconds, or {@code C.TIME_UNSET} when unknown (PGS, VobSub).
   * @param sizeBytes Sample payload size, useful to skip PGS clear-display segments.
   */
  void onSubtitleSample(int trackNumber, long startUs, long durationUs, int sizeBytes);
}
