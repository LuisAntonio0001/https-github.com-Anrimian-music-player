package com.github.anrimian.musicplayer.data.repositories.music;

import com.github.anrimian.musicplayer.data.preferences.SettingsPreferences;
import com.github.anrimian.musicplayer.data.repositories.music.comparators.composition.AlphabeticalCompositionComparator;
import com.github.anrimian.musicplayer.data.repositories.music.comparators.composition.AlphabeticalDescCompositionComparator;
import com.github.anrimian.musicplayer.data.repositories.music.comparators.composition.CreateDateCompositionComparator;
import com.github.anrimian.musicplayer.data.repositories.music.comparators.composition.CreateDateDescCompositionComparator;
import com.github.anrimian.musicplayer.data.repositories.music.comparators.folder.AlphabeticalDescFileComparator;
import com.github.anrimian.musicplayer.data.repositories.music.comparators.folder.AlphabeticalFileComparator;
import com.github.anrimian.musicplayer.data.repositories.music.comparators.folder.CreateDateDescFileComparator;
import com.github.anrimian.musicplayer.data.repositories.music.comparators.folder.CreateDateFileComparator;
import com.github.anrimian.musicplayer.data.repositories.music.folders.MusicFolderDataSource;
import com.github.anrimian.musicplayer.data.repositories.music.search.CompositionSearchFilter;
import com.github.anrimian.musicplayer.data.repositories.music.search.FileSourceSearchFilter;
import com.github.anrimian.musicplayer.data.storage.providers.music.StorageMusicDataSource;
import com.github.anrimian.musicplayer.domain.models.composition.Composition;
import com.github.anrimian.musicplayer.domain.models.composition.Order;
import com.github.anrimian.musicplayer.domain.models.composition.folders.FileSource;
import com.github.anrimian.musicplayer.domain.models.composition.folders.Folder;
import com.github.anrimian.musicplayer.domain.models.composition.folders.FolderFileSource;
import com.github.anrimian.musicplayer.domain.models.composition.folders.MusicFileSource;
import com.github.anrimian.musicplayer.domain.models.player.error.ErrorType;
import com.github.anrimian.musicplayer.domain.repositories.MusicProviderRepository;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import javax.annotation.Nullable;

import io.reactivex.Completable;
import io.reactivex.Observable;
import io.reactivex.Scheduler;
import io.reactivex.Single;

import static com.github.anrimian.musicplayer.domain.utils.search.ListSearchFilter.filterList;

/**
 * Created on 24.10.2017.
 */

public class MusicProviderRepositoryImpl implements MusicProviderRepository {

    private final StorageMusicDataSource storageMusicDataSource;
    private final MusicFolderDataSource musicFolderDataSource;
    private final SettingsPreferences settingsPreferences;
    private final Scheduler scheduler;

    public MusicProviderRepositoryImpl(StorageMusicDataSource storageMusicDataSource,
                                       MusicFolderDataSource musicFolderDataSource,
                                       SettingsPreferences settingsPreferences,
                                       Scheduler scheduler) {
        this.storageMusicDataSource = storageMusicDataSource;
        this.musicFolderDataSource = musicFolderDataSource;
        this.settingsPreferences = settingsPreferences;
        this.scheduler = scheduler;
    }

    @Override
    public Observable<List<Composition>> getAllCompositionsObservable(@Nullable String searchText) {
        return storageMusicDataSource.getCompositionObservable()
                .map(this::toSortedList)
                .map(list -> filterList(list, searchText, new CompositionSearchFilter()))
                .subscribeOn(scheduler);
    }

    @Override
    public Single<Folder> getCompositionsInPath(@Nullable String path,
                                                @Nullable String searchText) {
        return musicFolderDataSource.getCompositionsInPath(path)
                .doOnSuccess(folder -> folder.applyFileOrder(getSelectedFileComparatorObservable()))
                .doOnSuccess(folder -> folder.applySearchFilter(searchText, new FileSourceSearchFilter()))
                .subscribeOn(scheduler);
    }

    @Override
    public Single<List<Composition>> getAllCompositionsInPath(@Nullable String path) {
        return getCompositionsObservable(path)//FolderNodeNonExistException
                .toList()
                .subscribeOn(scheduler);
    }

    @Override
    public Single<List<String>> getAvailablePathsForPath(@Nullable String path) {
        return musicFolderDataSource.getAvailablePathsForPath(path)
                .subscribeOn(scheduler);
    }

    @Override
    public Completable writeErrorAboutComposition(ErrorType errorType, Composition composition) {
        return Completable.complete()//TODO write error about composition
                .subscribeOn(scheduler);
    }

    @Override
    public Completable deleteComposition(Composition composition) {
        return storageMusicDataSource.deleteComposition(composition)
                .subscribeOn(scheduler);
    }

    @Override
    public Completable deleteCompositions(List<Composition> compositions) {
        return storageMusicDataSource.deleteCompositions(compositions)
                .subscribeOn(scheduler);
    }

    private Comparator<FileSource> getFileComparator(Order order) {
        switch (order) {
            case ALPHABETICAL: return new AlphabeticalFileComparator();
            case ALPHABETICAL_DESC: return new AlphabeticalDescFileComparator();
            case ADD_TIME: return new CreateDateFileComparator();
            case ADD_TIME_DESC: return new CreateDateDescFileComparator();
            default: return new AlphabeticalFileComparator();
        }
    }

    private Comparator<FileSource> getSelectedFileComparator() {
        return getFileComparator(settingsPreferences.getFolderOrder());
    }

    private Observable<Comparator<FileSource>> getSelectedFileComparatorObservable() {
        return settingsPreferences.getFolderOrderObservable()
                .map(this::getFileComparator);
    }

    private Comparator<Composition> getCompositionComparator() {
        switch (settingsPreferences.getCompositionsOrder()) {
            case ALPHABETICAL: return new AlphabeticalCompositionComparator();
            case ALPHABETICAL_DESC: return new AlphabeticalDescCompositionComparator();
            case ADD_TIME: return new CreateDateCompositionComparator();
            case ADD_TIME_DESC: return new CreateDateDescCompositionComparator();
            default: return new AlphabeticalCompositionComparator();
        }
    }

    private Observable<Composition> getCompositionsObservable(@Nullable String path) {
        return musicFolderDataSource.getCompositionsInPath(path)
                .doOnSuccess(folder -> folder.applyFileOrder(this::getSelectedFileComparator))
                .flatMap(folder -> folder.getFilesObservable().firstOrError())
                .flatMapObservable(Observable::fromIterable)
                .flatMap(fileSource -> {
                    if (fileSource instanceof FolderFileSource) {
                        return getCompositionsObservable(((FolderFileSource) fileSource).getFullPath());
                    } else if (fileSource instanceof MusicFileSource) {
                        return Observable.just(((MusicFileSource) fileSource).getComposition());
                    }
                    throw new IllegalStateException("unexpected file source type: " + fileSource);
                });
    }

    private List<Composition> toSortedList(Map<Long, Composition> compositionMap) {
        List<Composition> list = new ArrayList<>(compositionMap.values());
        Collections.sort(list, getCompositionComparator());
        return list;
    }
}
