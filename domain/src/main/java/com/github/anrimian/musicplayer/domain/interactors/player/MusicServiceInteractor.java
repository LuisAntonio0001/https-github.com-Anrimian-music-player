package com.github.anrimian.musicplayer.domain.interactors.player;

import com.github.anrimian.musicplayer.domain.interactors.library.LibraryCompositionsInteractor;
import com.github.anrimian.musicplayer.domain.interactors.library.LibraryFoldersInteractor;
import com.github.anrimian.musicplayer.domain.models.composition.Composition;
import com.github.anrimian.musicplayer.domain.models.folders.FileSource;
import com.github.anrimian.musicplayer.domain.models.player.service.MusicNotificationSetting;
import com.github.anrimian.musicplayer.domain.repositories.SettingsRepository;

import java.util.List;

import javax.annotation.Nullable;

import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.core.Observable;

import static com.github.anrimian.musicplayer.domain.interactors.player.PlayerType.EXTERNAL;
import static com.github.anrimian.musicplayer.domain.interactors.player.PlayerType.LIBRARY;

public class MusicServiceInteractor {

    private final PlayerCoordinatorInteractor playerCoordinatorInteractor;
    private final LibraryPlayerInteractor libraryPlayerInteractor;
    private final ExternalPlayerInteractor externalPlayerInteractor;
    private final LibraryCompositionsInteractor libraryCompositionsInteractor;
    private final LibraryFoldersInteractor libraryFoldersInteractor;
    private final SettingsRepository settingsRepository;

    public MusicServiceInteractor(PlayerCoordinatorInteractor playerCoordinatorInteractor,
                                  LibraryPlayerInteractor libraryPlayerInteractor,
                                  ExternalPlayerInteractor externalPlayerInteractor,
                                  LibraryCompositionsInteractor libraryCompositionsInteractor,
                                  LibraryFoldersInteractor libraryFoldersInteractor,
                                  SettingsRepository settingsRepository) {
        this.playerCoordinatorInteractor = playerCoordinatorInteractor;
        this.libraryPlayerInteractor = libraryPlayerInteractor;
        this.externalPlayerInteractor = externalPlayerInteractor;
        this.libraryCompositionsInteractor = libraryCompositionsInteractor;
        this.libraryFoldersInteractor = libraryFoldersInteractor;
        this.settingsRepository = settingsRepository;
    }

    public void skipToNext() {
        if (playerCoordinatorInteractor.isPlayerTypeActive(LIBRARY)) {
            libraryPlayerInteractor.skipToNext();
        }
    }

    public void skipToPrevious() {
        if (playerCoordinatorInteractor.isPlayerTypeActive(LIBRARY)) {
            libraryPlayerInteractor.skipToPrevious();
        }
    }

    public void setRepeatMode(int appRepeatMode) {
        if (playerCoordinatorInteractor.isPlayerTypeActive(LIBRARY)) {
            libraryPlayerInteractor.setRepeatMode(appRepeatMode);
        } else {
            externalPlayerInteractor.setExternalPlayerRepeatMode(appRepeatMode);
        }
    }

    public void changeRepeatMode() {
        if (playerCoordinatorInteractor.isPlayerTypeActive(LIBRARY)) {
            libraryPlayerInteractor.changeRepeatMode();
        } else {
            externalPlayerInteractor.changeExternalPlayerRepeatMode();
        }
    }

    public void setRandomPlayingEnabled(boolean isEnabled) {
        libraryPlayerInteractor.setRandomPlayingEnabled(isEnabled);
    }

    public void setPlaybackSpeed(float speed) {
        if (playerCoordinatorInteractor.isPlayerTypeActive(LIBRARY)) {
            libraryPlayerInteractor.setPlaybackSpeed(speed);
            return;
        }
        if (playerCoordinatorInteractor.isPlayerTypeActive(EXTERNAL)) {
            externalPlayerInteractor.setPlaybackSpeed(speed);
        }
    }

    public Completable shuffleAllAndPlay() {
        return libraryCompositionsInteractor.getCompositionsObservable(null)
                .firstOrError()
                .doOnSuccess(compositions -> {
                    libraryPlayerInteractor.setRandomPlayingEnabled(true);
                    libraryPlayerInteractor.startPlaying(compositions);
                })
                .ignoreElement();
    }

    public Completable startPlayingFromCompositions(int position) {
        return libraryCompositionsInteractor.getCompositionsObservable(null)
                .firstOrError()
                .doOnSuccess(compositions -> libraryPlayerInteractor.startPlaying(compositions, position))
                .ignoreElement();
    }

    public Observable<Integer> getRepeatModeObservable() {
        return playerCoordinatorInteractor.getActivePlayerTypeObservable()
                .switchMap(playerType -> {
                    switch (playerType) {
                        case LIBRARY: {
                            return libraryPlayerInteractor.getRepeatModeObservable();
                        }
                        case EXTERNAL: {
                            return externalPlayerInteractor.getExternalPlayerRepeatModeObservable();
                        }
                        default: throw new IllegalStateException();
                    }
                });
    }

    public Observable<Boolean> getRandomModeObservable() {
        return playerCoordinatorInteractor.getActivePlayerTypeObservable()
                .switchMap(playerType -> {
                    switch (playerType) {
                        case LIBRARY: {
                            return libraryPlayerInteractor.getRandomPlayingObservable();
                        }
                        case EXTERNAL: {
                            return Observable.fromCallable(() -> false);
                        }
                        default: throw new IllegalStateException();
                    }
                });
    }

    public Observable<MusicNotificationSetting> getNotificationSettingObservable() {
        return Observable.combineLatest(getCoversInNotificationEnabledObservable(),
                getColoredNotificationEnabledObservable(),
                getCoversOnLockScreenEnabledObservable(),
                MusicNotificationSetting::new);
    }

    public Observable<List<Composition>> getCompositionsObservable() {
        return libraryCompositionsInteractor.getCompositionsObservable(null);
    }

    public Observable<List<FileSource>> getFoldersObservable(@Nullable Long folderId) {
        return libraryFoldersInteractor.getFoldersInFolder(folderId, null);
    }

    public void play(Long folderId, long compositionId) {
        libraryFoldersInteractor.play(folderId, compositionId);
    }

    public MusicNotificationSetting getNotificationSettings() {
        boolean coversEnabled = settingsRepository.isCoversEnabled();
        boolean coversInNotification = coversEnabled && settingsRepository.isCoversInNotificationEnabled();
        boolean coloredNotification = settingsRepository.isColoredNotificationEnabled();
        boolean coversOnLockScreen = settingsRepository.isCoversOnLockScreenEnabled();
        return new MusicNotificationSetting(
                coversInNotification,
                coversInNotification && coloredNotification,
                coversInNotification && coversOnLockScreen
        );
    }

    private Observable<Boolean> getCoversInNotificationEnabledObservable() {
        return Observable.combineLatest(settingsRepository.getCoversEnabledObservable(),
                settingsRepository.getCoversInNotificationEnabledObservable(),
                (coversEnabled, coversInNotification) -> coversEnabled && coversInNotification);
    }

    private Observable<Boolean> getColoredNotificationEnabledObservable() {
        return Observable.combineLatest(getCoversInNotificationEnabledObservable(),
                settingsRepository.getColoredNotificationEnabledObservable(),
                (coversInNotification, coloredNotification) -> coversInNotification && coloredNotification);
    }

    private Observable<Boolean> getCoversOnLockScreenEnabledObservable() {
        return Observable.combineLatest(getCoversInNotificationEnabledObservable(),
                settingsRepository.getCoversOnLockScreenEnabledObservable(),
                (coversInNotification, coversOnLockScreen) -> coversInNotification && coversOnLockScreen);
    }

}
