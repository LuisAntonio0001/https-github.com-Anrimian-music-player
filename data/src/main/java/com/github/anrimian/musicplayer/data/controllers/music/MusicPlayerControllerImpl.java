package com.github.anrimian.musicplayer.data.controllers.music;

import android.content.Context;
import android.net.Uri;

import com.github.anrimian.musicplayer.data.preferences.UiStatePreferences;
import com.github.anrimian.musicplayer.data.utils.exo_player.PlayerEventListener;
import com.github.anrimian.musicplayer.domain.controllers.MusicPlayerController;
import com.github.anrimian.musicplayer.domain.models.composition.Composition;
import com.github.anrimian.musicplayer.domain.models.player.events.ErrorEvent;
import com.github.anrimian.musicplayer.domain.models.player.events.PlayerEvent;
import com.github.anrimian.musicplayer.domain.models.player.events.PreparedEvent;
import com.google.android.exoplayer2.DefaultLoadControl;
import com.google.android.exoplayer2.DefaultRenderersFactory;
import com.google.android.exoplayer2.ExoPlayerFactory;
import com.google.android.exoplayer2.SimpleExoPlayer;
import com.google.android.exoplayer2.extractor.DefaultExtractorsFactory;
import com.google.android.exoplayer2.source.ExtractorMediaSource;
import com.google.android.exoplayer2.source.MediaSource;
import com.google.android.exoplayer2.trackselection.DefaultTrackSelector;
import com.google.android.exoplayer2.upstream.DataSource;
import com.google.android.exoplayer2.upstream.DataSpec;
import com.google.android.exoplayer2.upstream.FileDataSource;

import java.io.File;
import java.io.FileNotFoundException;
import java.util.concurrent.TimeUnit;

import javax.annotation.Nullable;

import io.reactivex.Observable;
import io.reactivex.Single;
import io.reactivex.disposables.Disposable;
import io.reactivex.subjects.BehaviorSubject;
import io.reactivex.subjects.PublishSubject;

/**
 * Created on 10.11.2017.
 */

public class MusicPlayerControllerImpl implements MusicPlayerController {

    private final BehaviorSubject<Long> trackPositionSubject = BehaviorSubject.create();

    private final PublishSubject<PlayerEvent> playerEventSubject = PublishSubject.create();

    private final SimpleExoPlayer player;
    private final UiStatePreferences uiStatePreferences;

    @Nullable
    private Disposable trackPositionDisposable;

    public MusicPlayerControllerImpl(UiStatePreferences uiStatePreferences, Context context) {
        this.uiStatePreferences = uiStatePreferences;
        player = ExoPlayerFactory.newSimpleInstance(
//                context,
                new DefaultRenderersFactory(context),
                new DefaultTrackSelector(),
                new DefaultLoadControl());

        PlayerEventListener playerEventListener = new PlayerEventListener(playerEventSubject);
        player.addListener(playerEventListener);
    }

    @Override
    public Observable<PlayerEvent> getEventsObservable() {
        return playerEventSubject;
    }

    @Override
    public void prepareToPlay(Composition composition, long startPosition) {
        checkComposition(composition)
                .flatMap(this::prepareMediaSource)
                .ignoreElement()
                .doOnEvent(t -> onCompositionPrepared(t, startPosition))
                .onErrorComplete()
                .subscribe();
    }

    @Override
    public void stop() {
        seekTo(0);
        player.stop();
        stopTracingTrackPosition();
        uiStatePreferences.setTrackPosition(0);
    }

    @Override
    public void pause() {
        player.setPlayWhenReady(false);
        stopTracingTrackPosition();
        uiStatePreferences.setTrackPosition(player.getCurrentPosition());
    }

    @Override
    public void seekTo(long position) {
        player.seekTo(position);
        trackPositionSubject.onNext(position);
        uiStatePreferences.setTrackPosition(player.getCurrentPosition());
    }

    @Override
    public void resume() {
        player.setPlayWhenReady(true);
        startTracingTrackPosition();
    }

    @Override
    public Observable<Long> getTrackPositionObservable() {
        return trackPositionSubject;
    }

    @Override
    public long getTrackPosition() {
        return player.getCurrentPosition();
    }

    private void onCompositionPrepared(Throwable throwable, long startPosition) {
        if (throwable == null) {
            seekTo(startPosition);
            playerEventSubject.onNext(new PreparedEvent());
        } else {
            seekTo(0);
            player.setPlayWhenReady(false);
            playerEventSubject.onNext(new ErrorEvent(throwable));
        }
    }

    private void startTracingTrackPosition() {
        stopTracingTrackPosition();
        trackPositionDisposable = Observable.interval(0, 1, TimeUnit.SECONDS)
                .map(o -> player.getCurrentPosition())
                .subscribe(trackPositionSubject::onNext);
    }

    private void stopTracingTrackPosition() {
        if (trackPositionDisposable != null) {
            trackPositionDisposable.dispose();
            trackPositionDisposable = null;
        }
    }

    private Single<Composition> checkComposition(Composition composition) {
        return Single.fromCallable(() -> {
            File file = new File(composition.getFilePath());
            if (!file.exists()) {
                throw new FileNotFoundException(composition.getFilePath() + " not found");
            }
           return composition;
        });
    }

    private Single<MediaSource> prepareMediaSource(Composition composition) {
        return Single.create(emitter -> {
            Uri uri = Uri.fromFile(new File(composition.getFilePath()));
            DataSpec dataSpec = new DataSpec(uri);
            final FileDataSource fileDataSource = new FileDataSource();
            fileDataSource.open(dataSpec);

            DataSource.Factory factory = () -> fileDataSource;
            MediaSource mediaSource = new ExtractorMediaSource(fileDataSource.getUri(),
                    factory,
                    new DefaultExtractorsFactory(),
                    null,
                    null);
            player.prepare(mediaSource);
            emitter.onSuccess(mediaSource);
        });
    }

}
