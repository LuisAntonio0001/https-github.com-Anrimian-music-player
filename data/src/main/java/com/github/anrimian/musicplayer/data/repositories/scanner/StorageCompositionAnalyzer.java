package com.github.anrimian.musicplayer.data.repositories.scanner;

import androidx.collection.LongSparseArray;

import com.github.anrimian.musicplayer.data.database.dao.compositions.CompositionsDaoWrapper;
import com.github.anrimian.musicplayer.data.database.dao.folders.FoldersDaoWrapper;
import com.github.anrimian.musicplayer.data.database.entities.folder.FolderEntity;
import com.github.anrimian.musicplayer.data.models.changes.Change;
import com.github.anrimian.musicplayer.data.repositories.scanner.nodes.AddedNode;
import com.github.anrimian.musicplayer.data.repositories.scanner.nodes.FolderInfo;
import com.github.anrimian.musicplayer.data.repositories.scanner.nodes.FolderNode;
import com.github.anrimian.musicplayer.data.repositories.scanner.nodes.FolderTreeBuilder;
import com.github.anrimian.musicplayer.data.repositories.scanner.nodes.Node;
import com.github.anrimian.musicplayer.data.storage.providers.albums.StorageAlbum;
import com.github.anrimian.musicplayer.data.storage.providers.music.StorageComposition;
import com.github.anrimian.musicplayer.data.storage.providers.music.StorageFullComposition;
import com.github.anrimian.musicplayer.data.utils.collections.AndroidCollectionUtils;
import com.github.anrimian.musicplayer.domain.utils.Objects;
import com.github.anrimian.musicplayer.domain.utils.validation.DateUtils;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;

import javax.annotation.Nullable;

import io.reactivex.Observable;

import static com.github.anrimian.musicplayer.domain.utils.Objects.requireNonNull;

class StorageCompositionAnalyzer {

    private final CompositionsDaoWrapper compositionsDao;
    private final FoldersDaoWrapper foldersDao;

    private final FolderTreeBuilder<StorageFullComposition, Long> folderTreeBuilder;

    StorageCompositionAnalyzer(CompositionsDaoWrapper compositionsDao,
                               FoldersDaoWrapper foldersDao) {
        this.compositionsDao = compositionsDao;
        this.foldersDao = foldersDao;

        folderTreeBuilder = new FolderTreeBuilder<>(
                StorageFullComposition::getRelativePath,
                StorageFullComposition::getId
        );
    }

    synchronized void applyCompositionsData(LongSparseArray<StorageFullComposition> newCompositions) {//at the end check file path to relative path migration
        FolderNode<Long> actualFolderTree = folderTreeBuilder.createFileTree(fromSparseArray(newCompositions));
        actualFolderTree = cutEmptyRootNodes(actualFolderTree);//save excluded part?

        excludeCompositions(actualFolderTree, newCompositions);

        Node<String, FolderEntity> existsFolders = createTreeFromIdMap(foldersDao.getAllFolders());

        List<Long> foldersToDelete = new LinkedList<>();
        List<AddedNode> foldersToInsert = new LinkedList<>();
        mergeFolderTrees(actualFolderTree, existsFolders, foldersToDelete, foldersToInsert);

        Set<Long> movedCompositions = getAffectedCompositions(foldersToInsert);
        LongSparseArray<StorageComposition> currentCompositions = compositionsDao.selectAllAsStorageCompositions();

        List<StorageFullComposition> addedCompositions = new ArrayList<>();
        List<StorageComposition> deletedCompositions = new ArrayList<>();
        List<Change<StorageComposition, StorageFullComposition>> changedCompositions = new ArrayList<>();
        boolean hasChanges = AndroidCollectionUtils.processDiffChanges(currentCompositions,
                newCompositions,
                (first, second) -> hasActualChanges(first, second) || movedCompositions.contains(first.getStorageId()),//is in folder change
                deletedCompositions::add,
                addedCompositions::add,
                (oldItem, newItem) -> changedCompositions.add(new Change<>(oldItem, newItem)));

        if (hasChanges) {
            LongSparseArray<Long> compositionIdMap = foldersDao.insertFolders(foldersToInsert);//how to test this?
            compositionsDao.applyChanges(addedCompositions,
                    deletedCompositions,
                    changedCompositions,//move folder change
                    compositionIdMap);//and this
            //delete old folders
        }
    }

    private Set<Long> getAffectedCompositions(List<AddedNode> nodes) {
        Set<Long> result = new LinkedHashSet<>();
        for (AddedNode addedNode: nodes) {
            result.addAll(getAllCompositionsInNode(addedNode.getNode()));
        }
        return result;
    }

    private void mergeFolderTrees(FolderNode<Long> actualFolderNode,
                                  Node<String, FolderEntity> existsFoldersNode,
                                  List<Long> foldersToDelete,
                                  List<AddedNode> foldersToInsert) {
        for (Node<String, FolderEntity> existFolder : existsFoldersNode.getNodes()) {
            String key = existFolder.getKey();
            if (key == null) {
                continue;//not a folder
            }

            FolderNode<Long> actualFolder = actualFolderNode.getFolder(key);
            if (actualFolder == null) {
                foldersToDelete.add(existFolder.getData().getId());
            }
        }
        for (FolderNode<Long> actualFolder : actualFolderNode.getFolders()) {
            String key = actualFolder.getKeyPath();

            Node<String, FolderEntity> existFolder = existsFoldersNode.getChild(key);
            if (existFolder == null) {
                Long parentId = null;
                FolderEntity entity = existsFoldersNode.getData();
                if (entity != null) {
                    parentId = entity.getId();
                }

                AddedNode addedNode = new AddedNode(parentId, actualFolder);
                foldersToInsert.add(addedNode);//we add unnecessary child folders, hm
            } else {
                mergeFolderTrees(actualFolder, existsFoldersNode, foldersToDelete, foldersToInsert);
            }
        }
    }

    private Node<String, FolderEntity> createTreeFromIdMap(List<FolderEntity> folders) {
        LongSparseArray<List<FolderEntity>> idMap = new LongSparseArray<>();

        Node<String, FolderEntity> rootNode = new Node<>(null, null);

        for (FolderEntity entity: folders) {
            Long parentId = entity.getParentId();
            if (parentId == null) {
                rootNode.addNode(new Node<>(entity.getName(), entity));
            } else {
                List<FolderEntity> childList = idMap.get(parentId);
                if (childList == null) {
                    childList = new LinkedList<>();
                    idMap.put(parentId, childList);
                }
                childList.add(entity);
            }
        }

        fillIdTree(rootNode, idMap);

        if (!idMap.isEmpty()) {
            throw new IllegalStateException("found missed folders");//called on live device, why
        }

        return rootNode;
    }

    private void fillIdTree(Node<String, FolderEntity> targetNode,
                            LongSparseArray<List<FolderEntity>> idMap) {
        for (Node<String, FolderEntity> childNode: targetNode.getNodes()) {
            FolderEntity folderEntity = childNode.getData();
            long id = folderEntity.getId();
            List<FolderEntity> childList = idMap.get(id);
            if (childList == null) {
                break;
            }
            for (FolderEntity entity: childList) {
                Node<String, FolderEntity> node = new Node<>(entity.getName(), entity);
                childNode.addNode(node);
                fillIdTree(node, idMap);
            }
            idMap.remove(id);
        }
    }

    private void excludeCompositions(FolderNode<Long> folderTree,
                                     LongSparseArray<StorageFullComposition> compositions) {
        String[] ignoresFolders = foldersDao.getIgnoredFolders();
        for (String ignoredFoldersPath: ignoresFolders) {

            FolderNode<Long> ignoreNode = findFolder(folderTree, ignoredFoldersPath);
            if (ignoreNode == null) {
                continue;
            }

            FolderNode<Long> parent = ignoreNode.getParentFolder();
            if (parent != null) {
                parent.removeFolder(ignoreNode.getKeyPath());
            }

            for (Long id: getAllCompositionsInNode(ignoreNode)) {
                compositions.remove(id);
            }
        }
    }

    private FolderNode<Long> cutEmptyRootNodes(FolderNode<Long> root) {
        FolderNode<Long> found = root;
        while (isEmptyFolderNode(found)) {
            found = found.getFirstFolder();
        }
        return found;
    }

    private boolean isEmptyFolderNode(FolderNode<Long> node) {
        return node.getFolders().size() == 1 && node.getFiles().isEmpty();
    }

    @Nullable
    private FolderNode<Long> findFolder(FolderNode<Long> folderTree, String path) {
        FolderNode<Long> currentNode = folderTree;
        for (String partialPath: path.split("/")) {
            currentNode = currentNode.getFolder(partialPath);

            if (currentNode == null) {
                //perhaps we can implement find. Find up and down on tree.
                return null;
            }
        }
        return currentNode;
    }

    private List<Long> getAllCompositionsInNode(FolderNode<Long> parentNode) {
        LinkedList<Long> result = new LinkedList<>(parentNode.getFiles());
        for (FolderNode<Long> node: parentNode.getFolders()) {
            result.addAll(getAllCompositionsInNode(node));
        }
        return result;
    }

    private List<FolderInfo> getAllFoldersInTree(Node<String, Long> parentNode) {
        LinkedList<FolderInfo> result = new LinkedList<>();
        result.add(new FolderInfo(parentNode.getKey(), null, getCompositionsInNode(parentNode)));
        for (Node<String, Long> node: parentNode.getNodes()) {
            if (node.getData() == null) {
                result.add(new FolderInfo(node.getKey(),
                        requireNonNull(node.getParent()).getKey(),
                        getCompositionsInNode(node))
                );
            } else {
                result.addAll(getAllFoldersInTree(node));
            }
        }
        return result;
    }

    private List<Long> getCompositionsInNode(Node<String, Long> parentNode) {
        LinkedList<Long> result = new LinkedList<>();
        for (Node<String, Long> node: parentNode.getNodes()) {
            if (node.getData() != null) {
                result.add(node.getData());
            }
        }
        return result;
    }

    private Observable<StorageFullComposition> fromSparseArray(
            LongSparseArray<StorageFullComposition> sparseArray) {
        return Observable.create(emitter -> {
            for(int i = 0, size = sparseArray.size(); i < size; i++) {
                StorageFullComposition existValue = sparseArray.valueAt(i);
                emitter.onNext(existValue);
            }
            emitter.onComplete();
        });
    }

    private boolean hasActualChanges(StorageComposition first, StorageFullComposition second) {
        if (!DateUtils.isAfter(second.getDateModified(), first.getDateModified())) {
            return false;
        }

        String newAlbumName = null;
        String newAlbumArtist = null;
        StorageAlbum newAlbum = second.getStorageAlbum();
        if (newAlbum != null) {
            newAlbumName = newAlbum.getAlbum();
            newAlbumArtist = newAlbum.getArtist();
        }

        return !(Objects.equals(first.getDateAdded(), second.getDateAdded())
                && first.getDuration() == second.getDuration()
                && Objects.equals(first.getFilePath(), second.getFilePath())
                && first.getSize() == second.getSize()
                && Objects.equals(first.getTitle(), second.getTitle())
                && Objects.equals(first.getArtist(), second.getArtist())
                && Objects.equals(first.getAlbum(), newAlbumName)
                && Objects.equals(first.getAlbumArtist(), newAlbumArtist));
    }
}
