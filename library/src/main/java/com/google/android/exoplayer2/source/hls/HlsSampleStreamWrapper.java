/*
 * Copyright (C) 2016 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.google.android.exoplayer2.source.hls;

import com.google.android.exoplayer2.C;
import com.google.android.exoplayer2.Format;
import com.google.android.exoplayer2.FormatHolder;
import com.google.android.exoplayer2.decoder.DecoderInputBuffer;
import com.google.android.exoplayer2.extractor.DefaultTrackOutput;
import com.google.android.exoplayer2.extractor.DefaultTrackOutput.UpstreamFormatChangedListener;
import com.google.android.exoplayer2.extractor.ExtractorOutput;
import com.google.android.exoplayer2.extractor.SeekMap;
import com.google.android.exoplayer2.source.AdaptiveMediaSourceEventListener.EventDispatcher;
import com.google.android.exoplayer2.source.SampleStream;
import com.google.android.exoplayer2.source.SequenceableLoader;
import com.google.android.exoplayer2.source.TrackGroup;
import com.google.android.exoplayer2.source.TrackGroupArray;
import com.google.android.exoplayer2.source.chunk.Chunk;
import com.google.android.exoplayer2.source.chunk.ChunkHolder;
import com.google.android.exoplayer2.trackselection.TrackSelection;
import com.google.android.exoplayer2.upstream.Allocator;
import com.google.android.exoplayer2.upstream.Loader;
import com.google.android.exoplayer2.util.Assertions;
import com.google.android.exoplayer2.util.MimeTypes;

import android.util.SparseArray;

import java.io.IOException;
import java.util.LinkedList;
import java.util.List;

/**
 * Loads {@link HlsMediaChunk}s obtained from a {@link HlsChunkSource}, and provides
 * {@link SampleStream}s from which the loaded media can be consumed.
 */
/* package */ final class HlsSampleStreamWrapper implements Loader.Callback<Chunk>,
    SequenceableLoader, ExtractorOutput, UpstreamFormatChangedListener {

  /**
   * A callback to be notified of events.
   */
  public interface Callback extends SequenceableLoader.Callback<HlsSampleStreamWrapper> {

    /**
     * Invoked when the wrapper has been prepared.
     */
    void onPrepared();

  }

  private static final int PRIMARY_TYPE_NONE = 0;
  private static final int PRIMARY_TYPE_TEXT = 1;
  private static final int PRIMARY_TYPE_AUDIO = 2;
  private static final int PRIMARY_TYPE_VIDEO = 3;

  private final int trackType;
  private final Callback callback;
  private final HlsChunkSource chunkSource;
  private final Allocator allocator;
  private final Format muxedAudioFormat;
  private final Format muxedCaptionFormat;
  private final int minLoadableRetryCount;
  private final Loader loader;
  private final EventDispatcher eventDispatcher;
  private final ChunkHolder nextChunkHolder;
  private final SparseArray<DefaultTrackOutput> sampleQueues;
  private final LinkedList<HlsMediaChunk> mediaChunks;

  private volatile boolean sampleQueuesBuilt;

  private boolean prepared;
  private int enabledTrackCount;
  private Format downstreamTrackFormat;
  private int upstreamChunkUid;

  // Tracks are complicated in HLS. See documentation of buildTracks for details.
  // Indexed by track (as exposed by this source).
  private TrackGroupArray trackGroups;
  private int primaryTrackGroupIndex;
  // Indexed by group.
  private boolean[] groupEnabledStates;

  private long lastSeekPositionUs;
  private long pendingResetPositionUs;

  private boolean loadingFinished;

  /**
   * @param trackType The type of the track. One of the {@link C} {@code TRACK_TYPE_*} constants.
   * @param callback A callback for the wrapper.
   * @param chunkSource A {@link HlsChunkSource} from which chunks to load are obtained.
   * @param allocator An {@link Allocator} from which to obtain media buffer allocations.
   * @param positionUs The position from which to start loading media.
   * @param muxedAudioFormat If HLS master playlist indicates that the stream contains muxed audio,
   *     this is the audio {@link Format} as defined by the playlist.
   * @param muxedCaptionFormat If HLS master playlist indicates that the stream contains muxed
   *     captions, this is the audio {@link Format} as defined by the playlist.
   * @param minLoadableRetryCount The minimum number of times that the source should retry a load
   *     before propagating an error.
   * @param eventDispatcher A dispatcher to notify of events.
   */
  public HlsSampleStreamWrapper(int trackType, Callback callback, HlsChunkSource chunkSource,
      Allocator allocator, long positionUs, Format muxedAudioFormat, Format muxedCaptionFormat,
      int minLoadableRetryCount, EventDispatcher eventDispatcher) {
    this.trackType = trackType;
    this.callback = callback;
    this.chunkSource = chunkSource;
    this.allocator = allocator;
    this.muxedAudioFormat = muxedAudioFormat;
    this.muxedCaptionFormat = muxedCaptionFormat;
    this.minLoadableRetryCount = minLoadableRetryCount;
    this.eventDispatcher = eventDispatcher;
    loader = new Loader("Loader:HlsSampleStreamWrapper");
    nextChunkHolder = new ChunkHolder();
    sampleQueues = new SparseArray<>();
    mediaChunks = new LinkedList<>();
    lastSeekPositionUs = positionUs;
    pendingResetPositionUs = positionUs;
  }

  public void prepare() {
    continueLoading(lastSeekPositionUs);
  }

  public void maybeThrowPrepareError() throws IOException {
    maybeThrowError();
  }

  public long getDurationUs() {
    return chunkSource.getDurationUs();
  }

  public int getEnabledTrackCount() {
    return enabledTrackCount;
  }

  public boolean isLive() {
    return chunkSource.isLive();
  }

  public TrackGroupArray getTrackGroups() {
    return trackGroups;
  }

  public SampleStream[] selectTracks(List<SampleStream> oldStreams,
      List<TrackSelection> newSelections, boolean isFirstTrackSelection) {
    Assertions.checkState(prepared);
    // Unselect old tracks.
    for (int i = 0; i < oldStreams.size(); i++) {
      int group = ((SampleStreamImpl) oldStreams.get(i)).group;
      setTrackGroupEnabledState(group, false);
      sampleQueues.valueAt(group).disable();
    }
    // Select new tracks.
    SampleStream[] newStreams = new SampleStream[newSelections.size()];
    for (int i = 0; i < newStreams.length; i++) {
      TrackSelection selection = newSelections.get(i);
      int group = trackGroups.indexOf(selection.group);
      int[] tracks = selection.getTracks();
      setTrackGroupEnabledState(group, true);
      if (group == primaryTrackGroupIndex) {
        chunkSource.selectTracks(new TrackSelection(chunkSource.getTrackGroup(), tracks));
      }
      newStreams[i] = new SampleStreamImpl(group);
    }
    // At the time of the first track selection all queues will be enabled, so we need to disable
    // any that are no longer required.
    if (isFirstTrackSelection) {
      int sampleQueueCount = sampleQueues.size();
      for (int i = 0; i < sampleQueueCount; i++) {
        if (!groupEnabledStates[i]) {
          sampleQueues.valueAt(i).disable();
        }
      }
    }
    // Cancel requests if necessary.
    if (enabledTrackCount == 0) {
      chunkSource.reset();
      downstreamTrackFormat = null;
      mediaChunks.clear();
      if (loader.isLoading()) {
        loader.cancelLoading();
      }
    }
    return newStreams;
  }

  public void seekTo(long positionUs) {
    lastSeekPositionUs = positionUs;
    pendingResetPositionUs = positionUs;
    loadingFinished = false;
    mediaChunks.clear();
    if (loader.isLoading()) {
      loader.cancelLoading();
    } else {
      int sampleQueueCount = sampleQueues.size();
      for (int i = 0; i < sampleQueueCount; i++) {
        sampleQueues.valueAt(i).reset(groupEnabledStates[i]);
      }
    }
  }

  public long getBufferedPositionUs() {
    if (loadingFinished) {
      return C.END_OF_SOURCE_US;
    } else if (isPendingReset()) {
      return pendingResetPositionUs;
    } else {
      long bufferedPositionUs = lastSeekPositionUs;
      HlsMediaChunk lastMediaChunk = mediaChunks.getLast();
      HlsMediaChunk lastCompletedMediaChunk = lastMediaChunk.isLoadCompleted() ? lastMediaChunk
          : mediaChunks.size() > 1 ? mediaChunks.get(mediaChunks.size() - 2) : null;
      if (lastCompletedMediaChunk != null) {
        bufferedPositionUs = Math.max(bufferedPositionUs, lastCompletedMediaChunk.endTimeUs);
      }
      int sampleQueueCount = sampleQueues.size();
      for (int i = 0; i < sampleQueueCount; i++) {
        bufferedPositionUs = Math.max(bufferedPositionUs,
            sampleQueues.valueAt(i).getLargestQueuedTimestampUs());
      }
      return bufferedPositionUs;
    }
  }

  public void release() {
    chunkSource.release();
    int sampleQueueCount = sampleQueues.size();
    for (int i = 0; i < sampleQueueCount; i++) {
      sampleQueues.valueAt(i).disable();
    }
    loader.release();
  }

  public long getLargestQueuedTimestampUs() {
    long largestQueuedTimestampUs = Long.MIN_VALUE;
    for (int i = 0; i < sampleQueues.size(); i++) {
      largestQueuedTimestampUs = Math.max(largestQueuedTimestampUs,
          sampleQueues.valueAt(i).getLargestQueuedTimestampUs());
    }
    return largestQueuedTimestampUs;
  }

  // SampleStream implementation.

  /* package */ boolean isReady(int group) {
    return loadingFinished || (!isPendingReset() && !sampleQueues.valueAt(group).isEmpty());
  }

  /* package */ void maybeThrowError() throws IOException {
    loader.maybeThrowError();
    chunkSource.maybeThrowError();
  }

  /* package */ int readData(int group, FormatHolder formatHolder, DecoderInputBuffer buffer) {
    if (isPendingReset()) {
      return C.RESULT_NOTHING_READ;
    }

    while (mediaChunks.size() > 1 && finishedReadingChunk(mediaChunks.getFirst())) {
      mediaChunks.removeFirst();
    }
    HlsMediaChunk currentChunk = mediaChunks.getFirst();
    Format trackFormat = currentChunk.trackFormat;
    if (!trackFormat.equals(downstreamTrackFormat)) {
      eventDispatcher.downstreamFormatChanged(trackType, trackFormat,
          currentChunk.trackSelectionReason, currentChunk.trackSelectionData,
          currentChunk.startTimeUs);
    }
    downstreamTrackFormat = trackFormat;

    return sampleQueues.valueAt(group).readData(formatHolder, buffer, loadingFinished,
        lastSeekPositionUs);
  }

  private boolean finishedReadingChunk(HlsMediaChunk chunk) {
    int chunkUid = chunk.uid;
    for (int i = 0; i < sampleQueues.size(); i++) {
      if (groupEnabledStates[i] && sampleQueues.valueAt(i).peekSourceId() == chunkUid) {
        return false;
      }
    }
    return true;
  }

  // SequenceableLoader implementation

  @Override
  public boolean continueLoading(long positionUs) {
    if (loader.isLoading()) {
      return false;
    }

    chunkSource.getNextChunk(mediaChunks.isEmpty() ? null : mediaChunks.getLast(),
        pendingResetPositionUs != C.UNSET_TIME_US ? pendingResetPositionUs : positionUs,
        nextChunkHolder);
    boolean endOfStream = nextChunkHolder.endOfStream;
    Chunk loadable = nextChunkHolder.chunk;
    nextChunkHolder.clear();

    if (endOfStream) {
      loadingFinished = true;
      return true;
    }

    if (loadable == null) {
      return false;
    }

    if (isMediaChunk(loadable)) {
      pendingResetPositionUs = C.UNSET_TIME_US;
      HlsMediaChunk mediaChunk = (HlsMediaChunk) loadable;
      mediaChunk.init(this);
      mediaChunks.add(mediaChunk);
    }
    long elapsedRealtimeMs = loader.startLoading(loadable, this, minLoadableRetryCount);
    eventDispatcher.loadStarted(loadable.dataSpec, loadable.type, trackType, loadable.trackFormat,
        loadable.trackSelectionReason, loadable.trackSelectionData, loadable.startTimeUs,
        loadable.endTimeUs, elapsedRealtimeMs);
    return true;
  }

  @Override
  public long getNextLoadPositionUs() {
    if (isPendingReset()) {
      return pendingResetPositionUs;
    } else {
      return loadingFinished ? C.END_OF_SOURCE_US : mediaChunks.getLast().endTimeUs;
    }
  }

  // Loader.Callback implementation.

  @Override
  public void onLoadCompleted(Chunk loadable, long elapsedRealtimeMs, long loadDurationMs) {
    chunkSource.onChunkLoadCompleted(loadable);
    eventDispatcher.loadCompleted(loadable.dataSpec, loadable.type, trackType, loadable.trackFormat,
        loadable.trackSelectionReason, loadable.trackSelectionData, loadable.startTimeUs,
        loadable.endTimeUs, elapsedRealtimeMs, loadDurationMs, loadable.bytesLoaded());
    if (!prepared) {
      continueLoading(lastSeekPositionUs);
    } else {
      callback.onContinueLoadingRequested(this);
    }
  }

  @Override
  public void onLoadCanceled(Chunk loadable, long elapsedRealtimeMs, long loadDurationMs,
      boolean released) {
    eventDispatcher.loadCanceled(loadable.dataSpec, loadable.type, trackType, loadable.trackFormat,
        loadable.trackSelectionReason, loadable.trackSelectionData, loadable.startTimeUs,
        loadable.endTimeUs, elapsedRealtimeMs, loadDurationMs, loadable.bytesLoaded());
    if (!released) {
      int sampleQueueCount = sampleQueues.size();
      for (int i = 0; i < sampleQueueCount; i++) {
        sampleQueues.valueAt(i).reset(groupEnabledStates[i]);
      }
      callback.onContinueLoadingRequested(this);
    }
  }

  @Override
  public int onLoadError(Chunk loadable, long elapsedRealtimeMs, long loadDurationMs,
      IOException error) {
    long bytesLoaded = loadable.bytesLoaded();
    boolean isMediaChunk = isMediaChunk(loadable);
    boolean cancelable = !isMediaChunk || bytesLoaded == 0;
    boolean canceled = false;
    if (chunkSource.onChunkLoadError(loadable, cancelable, error)) {
      if (isMediaChunk) {
        HlsMediaChunk removed = mediaChunks.removeLast();
        Assertions.checkState(removed == loadable);
        if (mediaChunks.isEmpty()) {
          pendingResetPositionUs = lastSeekPositionUs;
        }
      }
      canceled = true;
    }
    eventDispatcher.loadError(loadable.dataSpec, loadable.type, trackType, loadable.trackFormat,
        loadable.trackSelectionReason, loadable.trackSelectionData, loadable.startTimeUs,
        loadable.endTimeUs, elapsedRealtimeMs, loadDurationMs, loadable.bytesLoaded(), error,
        canceled);
    if (canceled) {
      if (!prepared) {
        continueLoading(lastSeekPositionUs);
      } else {
        callback.onContinueLoadingRequested(this);
      }
      return Loader.DONT_RETRY;
    } else {
      return Loader.RETRY;
    }
  }

  // Called by the consuming thread, but only when there is no loading thread.

  /**
   * Initializes the wrapper for loading a chunk.
   *
   * @param chunkUid The chunk's uid.
   * @param shouldSpliceIn Whether the samples parsed from the chunk should be spliced into any
   *     samples already queued to the wrapper.
   */
  public void init(int chunkUid, boolean shouldSpliceIn) {
    upstreamChunkUid = chunkUid;
    for (int i = 0; i < sampleQueues.size(); i++) {
      sampleQueues.valueAt(i).sourceId(chunkUid);
    }
    if (shouldSpliceIn) {
      for (int i = 0; i < sampleQueues.size(); i++) {
        sampleQueues.valueAt(i).splice();
      }
    }
  }

  // ExtractorOutput implementation. Called by the loading thread.

  @Override
  public DefaultTrackOutput track(int id) {
    if (sampleQueues.indexOfKey(id) >= 0) {
      return sampleQueues.get(id);
    }
    DefaultTrackOutput trackOutput = new DefaultTrackOutput(allocator);
    trackOutput.setUpstreamFormatChangeListener(this);
    trackOutput.sourceId(upstreamChunkUid);
    sampleQueues.put(id, trackOutput);
    return trackOutput;
  }

  @Override
  public void endTracks() {
    sampleQueuesBuilt = true;
    maybeFinishPrepare();
  }

  @Override
  public void seekMap(SeekMap seekMap) {
    // Do nothing.
  }

  // UpstreamFormatChangedListener implementation.

  @Override
  public void onUpstreamFormatChanged(Format format) {
    maybeFinishPrepare();
  }

  // Internal methods.

  private void maybeFinishPrepare() {
    if (prepared || !sampleQueuesBuilt) {
      return;
    }
    int sampleQueueCount = sampleQueues.size();
    for (int i = 0; i < sampleQueueCount; i++) {
      if (sampleQueues.valueAt(i).getUpstreamFormat() == null) {
        return;
      }
    }
    buildTracks();
    prepared = true;
    callback.onPrepared();
  }

  /**
   * Builds tracks that are exposed by this {@link HlsSampleStreamWrapper} instance, as well as
   * internal data-structures required for operation.
   * <p>
   * Tracks in HLS are complicated. A HLS master playlist contains a number of "variants". Each
   * variant stream typically contains muxed video, audio and (possibly) additional audio, metadata
   * and caption tracks. We wish to allow the user to select between an adaptive track that spans
   * all variants, as well as each individual variant. If multiple audio tracks are present within
   * each variant then we wish to allow the user to select between those also.
   * <p>
   * To do this, tracks are constructed as follows. The {@link HlsChunkSource} exposes (N+1) tracks,
   * where N is the number of variants defined in the HLS master playlist. These consist of one
   * adaptive track defined to span all variants and a track for each individual variant. The
   * adaptive track is initially selected. The extractor is then prepared to discover the tracks
   * inside of each variant stream. The two sets of tracks are then combined by this method to
   * create a third set, which is the set exposed by this {@link HlsSampleStreamWrapper}:
   * <ul>
   * <li>The extractor tracks are inspected to infer a "primary" track type. If a video track is
   * present then it is always the primary type. If not, audio is the primary type if present.
   * Else text is the primary type if present. Else there is no primary type.</li>
   * <li>If there is exactly one extractor track of the primary type, it's expanded into (N+1)
   * exposed tracks, all of which correspond to the primary extractor track and each of which
   * corresponds to a different chunk source track. Selecting one of these tracks has the effect
   * of switching the selected track on the chunk source.</li>
   * <li>All other extractor tracks are exposed directly. Selecting one of these tracks has the
   * effect of selecting an extractor track, leaving the selected track on the chunk source
   * unchanged.</li>
   * </ul>
   */
  private void buildTracks() {
    // Iterate through the extractor tracks to discover the "primary" track type, and the index
    // of the single track of this type.
    int primaryExtractorTrackType = PRIMARY_TYPE_NONE;
    int primaryExtractorTrackIndex = -1;
    int extractorTrackCount = sampleQueues.size();
    for (int i = 0; i < extractorTrackCount; i++) {
      String sampleMimeType = sampleQueues.valueAt(i).getUpstreamFormat().sampleMimeType;
      int trackType;
      if (MimeTypes.isVideo(sampleMimeType)) {
        trackType = PRIMARY_TYPE_VIDEO;
      } else if (MimeTypes.isAudio(sampleMimeType)) {
        trackType = PRIMARY_TYPE_AUDIO;
      } else if (MimeTypes.isText(sampleMimeType)) {
        trackType = PRIMARY_TYPE_TEXT;
      } else {
        trackType = PRIMARY_TYPE_NONE;
      }
      if (trackType > primaryExtractorTrackType) {
        primaryExtractorTrackType = trackType;
        primaryExtractorTrackIndex = i;
      } else if (trackType == primaryExtractorTrackType && primaryExtractorTrackIndex != -1) {
        // We have multiple tracks of the primary type. We only want an index if there only
        // exists a single track of the primary type, so set the index back to -1.
        primaryExtractorTrackIndex = -1;
      }
    }

    TrackGroup chunkSourceTrackGroup = chunkSource.getTrackGroup();
    int chunkSourceTrackCount = chunkSourceTrackGroup.length;

    // Instantiate the necessary internal data-structures.
    primaryTrackGroupIndex = -1;
    groupEnabledStates = new boolean[extractorTrackCount];

    // Construct the set of exposed track groups.
    TrackGroup[] trackGroups = new TrackGroup[extractorTrackCount];
    for (int i = 0; i < extractorTrackCount; i++) {
      Format sampleFormat = sampleQueues.valueAt(i).getUpstreamFormat();
      if (i == primaryExtractorTrackIndex) {
        Format[] formats = new Format[chunkSourceTrackCount];
        for (int j = 0; j < chunkSourceTrackCount; j++) {
          formats[j] = getSampleFormat(chunkSourceTrackGroup.getFormat(j), sampleFormat);
        }
        trackGroups[i] = new TrackGroup(formats);
        primaryTrackGroupIndex = i;
      } else {
        Format trackFormat = null;
        if (primaryExtractorTrackType == PRIMARY_TYPE_VIDEO) {
          if (MimeTypes.isAudio(sampleFormat.sampleMimeType)) {
            trackFormat = muxedAudioFormat;
          } else if (MimeTypes.APPLICATION_EIA608.equals(sampleFormat.sampleMimeType)) {
            trackFormat = muxedCaptionFormat;
          }
        }
        trackGroups[i] = new TrackGroup(getSampleFormat(trackFormat, sampleFormat));
      }
    }
    this.trackGroups = new TrackGroupArray(trackGroups);
  }

  /**
   * Enables or disables a specified track group.
   *
   * @param group The index of the track group.
   * @param enabledState True if the group is being enabled, or false if it's being disabled.
   */
  private void setTrackGroupEnabledState(int group, boolean enabledState) {
    Assertions.checkState(groupEnabledStates[group] != enabledState);
    groupEnabledStates[group] = enabledState;
    enabledTrackCount = enabledTrackCount + (enabledState ? 1 : -1);
  }

  /**
   * Derives a sample format corresponding to a given container format, by combining it with sample
   * level information obtained from a second sample format.
   *
   * @param containerFormat The container format for which the sample format should be derived.
   * @param sampleFormat A sample format from which to obtain sample level information.
   * @return The derived sample format.
   */
  private static Format getSampleFormat(Format containerFormat, Format sampleFormat) {
    if (containerFormat == null) {
      return sampleFormat;
    }
    return sampleFormat.copyWithContainerInfo(containerFormat.id, containerFormat.bitrate,
        containerFormat.width, containerFormat.height, containerFormat.selectionFlags,
        containerFormat.language);
  }

  private boolean isMediaChunk(Chunk chunk) {
    return chunk instanceof HlsMediaChunk;
  }

  private boolean isPendingReset() {
    return pendingResetPositionUs != C.UNSET_TIME_US;
  }

  private final class SampleStreamImpl implements SampleStream {

    private final int group;

    public SampleStreamImpl(int group) {
      this.group = group;
    }

    @Override
    public boolean isReady() {
      return HlsSampleStreamWrapper.this.isReady(group);
    }

    @Override
    public void maybeThrowError() throws IOException {
      HlsSampleStreamWrapper.this.maybeThrowError();
    }

    @Override
    public int readData(FormatHolder formatHolder, DecoderInputBuffer buffer) {
      return HlsSampleStreamWrapper.this.readData(group, formatHolder, buffer);
    }

    @Override
    public void skipToKeyframeBefore(long timeUs) {
      sampleQueues.valueAt(group).skipToKeyframeBefore(timeUs);
    }

  }

}
