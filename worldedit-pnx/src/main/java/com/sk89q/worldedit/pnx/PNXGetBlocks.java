package com.sk89q.worldedit.pnx;

import cn.nukkit.blockentity.BlockEntity;
import cn.nukkit.entity.Entity;
import cn.nukkit.level.Level;
import cn.nukkit.level.Location;
import cn.nukkit.level.biome.Biome;
import cn.nukkit.level.format.ChunkSection3DBiome;
import cn.nukkit.level.format.anvil.Chunk;
import cn.nukkit.level.format.anvil.ChunkSection;
import cn.nukkit.level.format.generic.EmptyChunkSection;
import com.fastasyncworldedit.core.Fawe;
import com.fastasyncworldedit.core.FaweCache;
import com.fastasyncworldedit.core.configuration.Settings;
import com.fastasyncworldedit.core.extent.processor.heightmap.HeightMapType;
import com.fastasyncworldedit.core.queue.IChunkGet;
import com.fastasyncworldedit.core.queue.IChunkSet;
import com.fastasyncworldedit.core.queue.implementation.QueueHandler;
import com.fastasyncworldedit.core.queue.implementation.blocks.CharGetBlocks;
import com.google.common.base.Preconditions;
import com.sk89q.jnbt.CompoundTag;
import com.sk89q.jnbt.ListTag;
import com.sk89q.jnbt.StringTag;
import com.sk89q.jnbt.Tag;
import com.sk89q.worldedit.internal.util.LogManagerCompat;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldedit.world.biome.BiomeType;
import com.sk89q.worldedit.world.biome.BiomeTypes;
import com.sk89q.worldedit.world.block.BaseBlock;
import com.sk89q.worldedit.world.block.BlockState;
import com.sk89q.worldedit.world.block.BlockTypes;
import com.sk89q.worldedit.world.block.BlockTypesCache;
import com.sk89q.worldedit.world.entity.EntityType;
import org.apache.logging.log4j.Logger;

import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Future;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.stream.Collectors;

public class PNXGetBlocks extends CharGetBlocks {

    private static final Logger LOGGER = LogManagerCompat.getLogger();
    private final ReadWriteLock sectionLock = new ReentrantReadWriteLock();
    private final Level serverLevel;
    private final int chunkX;
    private final int chunkZ;
    private final int minHeight;
    private final int maxHeight;
    private final int minSectionPosition;
    private final int maxSectionPosition;
    private boolean createCopy = false;
    private PNXGetBlocks_Copy copy = null;
    private boolean forceLoadSections = true;
    private boolean lightUpdate = false;
    private cn.nukkit.level.format.ChunkSection[] pnxChunkSections;
    private cn.nukkit.level.format.anvil.Chunk pnxChunk;

    public PNXGetBlocks(Level serverLevel, int chunkX, int chunkZ) {
        super((serverLevel.getMinHeight() + 1) / 16, serverLevel.getMaxHeight() / 16);
        this.serverLevel = serverLevel;
        this.chunkX = chunkX;
        this.chunkZ = chunkZ;
        this.minHeight = serverLevel.getMinHeight() + 1;
        this.maxHeight = serverLevel.getMaxHeight();
        this.minSectionPosition = this.minHeight / 16;
        this.maxSectionPosition = this.maxHeight / 16;

        Chunk chunk = (Chunk) serverLevel.getChunk(chunkX, chunkZ);
        if (chunk == null || !chunk.isGenerated()) {
            serverLevel.generateChunk(chunkX, chunkZ);
            chunk = (Chunk) serverLevel.getChunk(chunkX, chunkZ);
        }

        this.pnxChunk = chunk;
        this.pnxChunkSections = this.pnxChunk.getSections();
    }

    @Override
    public BaseBlock getFullBlock(final int x, final int y, final int z) {
        return this.getBlock(x, y, z).toBaseBlock();
    }

    @Override
    public BlockState getBlock(final int x, final int y, final int z) {
        return PNXAdapter.adapt(this.pnxChunk.getBlockState(x & 15, y, z & 15));
    }

    public int getChunkX() {
        return chunkX;
    }

    public int getChunkZ() {
        return chunkZ;
    }

    public Level getServerLevel() {
        return serverLevel;
    }

    @Override
    public int getMaxSectionPosition() {
        return this.maxSectionPosition;
    }

    @Override
    public int getMinSectionPosition() {
        return this.minSectionPosition;
    }

    @Override
    public boolean isCreateCopy() {
        return createCopy;
    }

    @Override
    public void setCreateCopy(boolean createCopy) {
        this.createCopy = createCopy;
    }

    @Override
    public IChunkGet getCopy() {
        return copy;
    }

    @Override
    public void setLightingToGet(char[][] light, int minSectionPosition, int maxSectionPosition) {
        if (light != null) {
            lightUpdate = true;
            try {
                fillLightNibble(light, LightLayer.BLOCK, minSectionPosition, maxSectionPosition);
            } catch (Throwable e) {
                e.printStackTrace();
            }
        }
    }

    @Override
    public void setSkyLightingToGet(char[][] light, int minSectionPosition, int maxSectionPosition) {
        if (light != null) {
            lightUpdate = true;
            try {
                fillLightNibble(light, LightLayer.SKY, minSectionPosition, maxSectionPosition);
            } catch (Throwable e) {
                e.printStackTrace();
            }
        }
    }

    enum LightLayer {
        SKY,
        BLOCK
    }

    @Override
    public void setHeightmapToGet(HeightMapType type, int[] data) {
        Preconditions.checkArgument(data.length == 256);
        for (int i = 0; i < data.length; i++) {
            pnxChunk.getHeightMapArray()[i] = (byte) data[i];
        }
    }

    @Override
    public int getMaxY() {
        return maxHeight;
    }

    @Override
    public int getMinY() {
        return minHeight;
    }

    @Override
    public BiomeType getBiomeType(int x, int y, int z) {
        return PNXAdapter.adapt(Biome.getBiome(pnxChunk.getBiomeId(x & 15, y, z & 15)));
    }

    @Override
    public void removeSectionLighting(int layer, boolean sky) {
        layer -= getMinSectionPosition();
        if (this.pnxChunkSections[layer] != null && !(this.pnxChunkSections[layer] instanceof EmptyChunkSection)) {
            lightUpdate = true;
            synchronized (this.pnxChunkSections[layer]) {
                for (int x = 0; x < 16; x++) {
                    for (int y = 0; y < 16; y++) {
                        for (int z = 0; z < 16; z++) {
                            this.pnxChunkSections[layer].setBlockLight(x, y, z, 0);
                        }
                    }
                }
            }
            if (sky) {
                synchronized (this.pnxChunkSections[layer]) {
                    for (int x = 0; x < 16; x++) {
                        for (int y = 0; y < 16; y++) {
                            for (int z = 0; z < 16; z++) {
                                this.pnxChunkSections[layer].setBlockSkyLight(x, y, z, 0);
                            }
                        }
                    }
                }
            }
        }
    }

    @Override
    public CompoundTag getTile(int x, int y, int z) {
        return new CompoundTag(NBTConverter.fromNative(this.pnxChunk.getTile(x & 15, y, z & 15).namedTag));
    }

    @Override
    public Map<BlockVector3, CompoundTag> getTiles() {
        var map = new HashMap<BlockVector3, CompoundTag>();
        this.pnxChunk.getBlockEntities().values()
                .forEach(entity -> map.put(
                        BlockVector3.at(entity.getFloorX(), entity.getFloorY(), entity.getFloorZ()),
                        new CompoundTag(NBTConverter.fromNative(entity.namedTag))
                ));
        return map;
    }

    @Override
    public int getSkyLight(int x, int y, int z) {
        return this.pnxChunk.getBlockSkyLight(x & 15, y, z & 15);
    }

    @Override
    public int getEmittedLight(int x, int y, int z) {
        return this.pnxChunk.getBlockLight(x & 15, y, z & 15);
    }

    @Override
    public int[] getHeightMap(HeightMapType type) {
        int[] array = new int[256];
        for (int i = 0; i < this.pnxChunk.getHeightMapArray().length; i++) {
            array[i] = this.pnxChunk.getHeightMapArray()[i];
        }
        return array;
    }

    @Override
    public CompoundTag getEntity(UUID uuid) {
        return new CompoundTag(this.pnxChunk.getEntities().values().stream()
                .filter(entity -> entity.getUniqueId().equals(uuid))
                .map(entity -> NBTConverter.fromNative(entity.namedTag))
                .toList().get(0));
    }

    @Override
    public Set<CompoundTag> getEntities() {
        return this.pnxChunk.getEntities().values().stream()
                .map(entity -> new CompoundTag(NBTConverter.fromNative(entity.namedTag)))
                .collect(Collectors.toSet());
    }

    private void removeEntity(Entity entity) {
        entity.kill();
    }

    public cn.nukkit.level.format.anvil.Chunk ensureLoaded(Level nmsWorld, int chunkX, int chunkZ) {
        return (cn.nukkit.level.format.anvil.Chunk) nmsWorld.getChunkIfLoaded(chunkX, chunkZ);
    }

    private void setChunkBlocks(final IChunkSet set) {
        for (int x = 0; x < 16; x++) {
            for (int y = set.getMinSectionPosition() * 16; y < set.getMaxSectionPosition() * 16 + 16; y++) {
                for (int z = 0; z < 16; z++) {
                    BlockState combined = set.getBlock(x, y, z);
                    if (combined.getBlockType() == BlockTypes.__RESERVED__) {
                        continue;
                    }
                    if (combined.getBlockType() == BlockTypes.AIR ||
                            combined.getBlockType() == BlockTypes.CAVE_AIR ||
                            combined.getBlockType() == BlockTypes.VOID_AIR) {
                        pnxChunk.setBlockState(x, y, z, cn.nukkit.blockstate.BlockState.AIR);
                    } else {
                        pnxChunk.setBlockState(x, y, z, PNXAdapter.adapt(combined));
                    }
                }
            }
        }
    }

    private void setSectionBlocks(final IChunkSet set) {
        for (int x = 0; x < 16; x++) {
            for (int y = set.getMinSectionPosition() * 16; y < set.getMaxSectionPosition() * 16 + 16; y++) {
                for (int z = 0; z < 16; z++) {
                    BlockState combined = set.getBlock(x, y, z);
                    if (combined.getBlockType() == BlockTypes.__RESERVED__) {
                        continue;
                    }
                    if (combined.getBlockType() == BlockTypes.AIR ||
                            combined.getBlockType() == BlockTypes.CAVE_AIR ||
                            combined.getBlockType() == BlockTypes.VOID_AIR) {
                        pnxChunk.setBlockState(x, y, z, cn.nukkit.blockstate.BlockState.AIR);
                    } else {
                        pnxChunk.setBlockState(x, y, z, PNXAdapter.adapt(combined));
                    }
                }
            }
        }
    }

    @Override
    @SuppressWarnings("rawtypes")
    public synchronized <T extends Future<T>> T call(IChunkSet set, Runnable finalizer) {
        forceLoadSections = false;
        copy = createCopy ? new PNXGetBlocks_Copy(serverLevel, pnxChunk) : null;
        try {
            Level nmsWorld = serverLevel;
            cn.nukkit.level.format.anvil.Chunk nmsChunk = ensureLoaded(nmsWorld, chunkX, chunkZ);
            // Remove existing tiles. Create a copy so that we can remove blocks
            Map<Long, BlockEntity> chunkTiles = new HashMap<>(nmsChunk.getBlockEntities());
            if (!chunkTiles.isEmpty()) {
                for (Map.Entry<Long, BlockEntity> entry : chunkTiles.entrySet()) {
                    final cn.nukkit.math.BlockVector3 pos = entry.getValue().getLocation().asBlockVector3();
                    final int lx = pos.getX() & 15;
                    final int ly = pos.getY();
                    final int lz = pos.getZ() & 15;
                    final int layer = ly >> 4;
                    if (!set.hasSection(layer)) {
                        continue;
                    }

                    int ordinal = set.getBlock(lx, ly, lz).getOrdinal();
                    if (ordinal != 0) {
                        BlockEntity tile = entry.getValue();
                        nmsChunk.removeBlockEntity(tile);
                        if (createCopy) {
                            copy.storeTile(tile);
                        }
                    }
                }
            }
            final BiomeType[][] biomes = set.getBiomes();
            int bitMask = 0;
            synchronized (nmsChunk) {
                //set section biome
                for (int layerNo = getMinSectionPosition(); layerNo <= getMaxSectionPosition(); layerNo++) {
                    int getSectionIndex = layerNo - getMinSectionPosition();
                    int setSectionIndex = layerNo - set.getMinSectionPosition();
                    int sectionIndex = sectionCount - getMinSectionPosition();
                    //store section
                    copy.storeSection(getSectionIndex, loadPrivately(layerNo));
                    if (!set.hasSection(layerNo)) {
                        if (biomes == null) {
                            continue;
                        }
                        if (layerNo < set.getMinSectionPosition() || layerNo > set.getMaxSectionPosition()) {
                            continue;
                        }
                        final BiomeType[] biome = biomes[setSectionIndex];
                        if (biome != null) {
                            synchronized (super.sectionLocks[getSectionIndex]) {
                                var existingSection = pnxChunkSections[getSectionIndex];
                                if (existingSection == null) {
                                    var newSection = new cn.nukkit.level.format.anvil.ChunkSection(layerNo);
                                    setSectionBiomes(biome, newSection);
                                    updateGet(pnxChunk, pnxChunkSections, newSection, new char[4096], getSectionIndex);
                                } else if (existingSection instanceof cn.nukkit.level.format.anvil.ChunkSection full) {
                                    setSectionBiomes(biome, full);
                                }
                                if (createCopy) {
                                    assert existingSection != null;
                                    copy.storeBiomes(
                                            getSectionIndex,
                                            ((ChunkSection3DBiome) existingSection).get3DBiomeDataArray()
                                    );
                                }
                            }
                        }
                        continue;
                    }
                    bitMask |= 1 << sectionIndex;

                    char[] tmp = set.load(layerNo);
                    char[] setArr = new char[4096];
                    System.arraycopy(tmp, 0, setArr, 0, 4096);

                    synchronized (super.sectionLocks[getSectionIndex]) {
                        var existingSection = pnxChunkSections[getSectionIndex];

                        if (createCopy) {
                            copy.storeSection(getSectionIndex, loadPrivately(layerNo));
                            if (biomes != null && existingSection != null) {
                                copy.storeBiomes(getSectionIndex, ((ChunkSection3DBiome) existingSection).get3DBiomeDataArray());
                            }
                        }

                        if (existingSection == null) {
                            BiomeType[] biomeData;
                            if (biomes == null) {
                                biomeData = new BiomeType[64];
                                Arrays.fill(biomeData, BiomeTypes.PLAINS);
                            } else {
                                biomeData = biomes[setSectionIndex];
                            }
                            var newSection = new cn.nukkit.level.format.anvil.ChunkSection(layerNo);
                            if (biomeData != null) {
                                setSectionBiomes(biomeData, newSection);
                                for (int y = 0, index = 0; y < 16; y++) {
                                    for (int z = 0; z < 16; z++) {
                                        for (int x = 0; x < 16; x++, index++) {
                                            BlockState combined = BlockState.getFromOrdinal(setArr[index]);
                                            if (combined.getBlockType() == BlockTypes.__RESERVED__) {
                                                continue;
                                            }
                                            if (combined.getBlockType() == BlockTypes.AIR ||
                                                    combined.getBlockType() == BlockTypes.CAVE_AIR ||
                                                    combined.getBlockType() == BlockTypes.VOID_AIR) {
                                                pnxChunk.setBlockState(x, y, z, cn.nukkit.blockstate.BlockState.AIR);
                                            } else {
                                                pnxChunk.setBlockState(x, y, z, PNXAdapter.adapt(combined));
                                            }
                                        }
                                    }
                                }
                            }
                            updateGet(pnxChunk, pnxChunkSections, newSection, setArr, getSectionIndex);
                        }
                        //同步
                        try {
                            sectionLock.writeLock().lock();
                            if (this.getChunk() != nmsChunk) {
                                this.pnxChunk = nmsChunk;
                                this.pnxChunkSections = null;
                                this.reset();
                            } else if (existingSection != getSections(false)[getSectionIndex]) {
                                this.pnxChunkSections[getSectionIndex] = existingSection;
                                this.reset();
                            } else if (!Arrays.equals(update(getSectionIndex, new char[4096], true), loadPrivately(layerNo))) {
                                this.reset(layerNo);
                            }
                        } finally {
                            sectionLock.writeLock().unlock();
                        }
                    }
                }
                //set block and state
                setChunkBlocks(set);
                //set Height Map
                Map<HeightMapType, int[]> heightMaps = set.getHeightMaps();
                for (Map.Entry<HeightMapType, int[]> entry : heightMaps.entrySet()) {
                    this.setHeightmapToGet(entry.getKey(), entry.getValue());
                }
                //set Lighting
                this.setLightingToGet(
                        set.getLight(),
                        set.getMinSectionPosition(),
                        set.getMaxSectionPosition()
                );
                //set SkyLighting
                this.setSkyLightingToGet(
                        set.getSkyLight(),
                        set.getMinSectionPosition(),
                        set.getMaxSectionPosition()
                );

                Runnable[] syncTasks = new Runnable[3];
                int bx = chunkX << 4;
                int bz = chunkZ << 4;

                //Remove Entity
                Set<UUID> entityRemoves = set.getEntityRemoves();
                if (entityRemoves != null && !entityRemoves.isEmpty()) {
                    syncTasks[2] = () -> {
                        Set<UUID> entitiesRemoved = new HashSet<>();
                        final var entities = nmsChunk.getEntities().values().iterator();
                        while (entities.hasNext()) {
                            var entity = entities.next();
                            var uuid = entity.getUniqueId();
                            if (entityRemoves.contains(uuid)) {
                                if (createCopy) {
                                    copy.storeEntity(entity);
                                }
                                removeEntity(entity);
                                entitiesRemoved.add(uuid);
                                entityRemoves.remove(uuid);
                                entities.remove();
                            }
                        }
                        if (Settings.settings().EXPERIMENTAL.REMOVE_ENTITY_FROM_WORLD_ON_CHUNK_FAIL) {
                            for (UUID uuid : entityRemoves) {
                                Entity entity = Arrays.stream(nmsWorld.getEntities()).filter(entity1 -> entity1
                                        .getUniqueId()
                                        .equals(uuid)).toList().get(0);
                                if (entity != null) {
                                    entitiesRemoved.add(uuid);
                                    removeEntity(entity);
                                }
                            }
                        }
                        // Only save entities that were actually removed to history
                        set.getEntityRemoves().clear();
                        set.getEntityRemoves().addAll(entitiesRemoved);
                    };
                }

                //set Entity
                Set<CompoundTag> entities = set.getEntities();
                if (entities != null && !entities.isEmpty()) {
                    syncTasks[1] = () -> {
                        for (final CompoundTag nativeTag : entities) {
                            final Map<String, Tag> entityTagMap = nativeTag.getValue();
                            final StringTag idTag = (StringTag) entityTagMap.get("Id");
                            final ListTag posTag = (ListTag) entityTagMap.get("Pos");
                            final ListTag rotTag = (ListTag) entityTagMap.get("Rotation");
                            if (idTag == null || posTag == null || rotTag == null) {
                                LOGGER.error("Unknown entity tag: {}", nativeTag);
                                continue;
                            }
                            final double x = posTag.getDouble(0);
                            final double y = posTag.getDouble(1);
                            final double z = posTag.getDouble(2);
                            final float yaw = rotTag.getFloat(0);
                            final float pitch = rotTag.getFloat(1);
                            final String id = idTag.getValue();

                            final EntityType entityType = EntityType.REGISTRY.get(id);
                            if (entityType != null) {
                                Entity entity = PNXAdapter.adaptEntityType(entityType);
                                if (entity != null) {
                                    entity.setPosition(new Location(x, y, z, yaw, pitch, nmsWorld));
                                    entity.spawnToAll();
                                }
                            }
                        }
                    };
                }

                // set tiles
                Map<BlockVector3, CompoundTag> tiles = set.getTiles();
                if (tiles != null && !tiles.isEmpty()) {
                    syncTasks[0] = () -> {
                        for (final Map.Entry<BlockVector3, CompoundTag> entry : tiles.entrySet()) {
                            final CompoundTag nativeTag = entry.getValue();
                            final BlockVector3 blockHash = entry.getKey();
                            final int x = blockHash.getX() + bx;
                            final int y = blockHash.getY();
                            final int z = blockHash.getZ() + bz;
                            final cn.nukkit.math.BlockVector3 pos = new cn.nukkit.math.BlockVector3(x, y, z);

                            synchronized (nmsWorld) {
                                BlockEntity tileEntity = nmsWorld.getBlockEntity(pos);
                                if (tileEntity == null || tileEntity.closed) {
                                    nmsWorld.removeBlockEntity(tileEntity);
                                    tileEntity = nmsWorld.getBlockEntity(pos);
                                }
                                if (tileEntity != null) {
                                    cn.nukkit.nbt.tag.CompoundTag tag = (cn.nukkit.nbt.tag.CompoundTag) NBTConverter.toNative(
                                            nativeTag.asBinaryTag());
                                    String tileId = tag.getString("id");
                                    Map<String, cn.nukkit.nbt.tag.Tag> map = new HashMap<>();
                                    map.put("x", new cn.nukkit.nbt.tag.IntTag("x", x));
                                    map.put("y", new cn.nukkit.nbt.tag.IntTag("y", y));
                                    map.put("z", new cn.nukkit.nbt.tag.IntTag("z", z));
                                    BlockEntity ent = BlockEntity.createBlockEntity(
                                            tileId,
                                            nmsChunk,
                                            new cn.nukkit.nbt.tag.CompoundTag(map)
                                    );
                                    if (ent != null) {
                                        nmsChunk.addBlockEntity(ent);
                                    }
                                }
                            }
                        }
                    };
                }

                Runnable callback;
                if (bitMask == 0 && set.getBiomes() == null && !lightUpdate) {
                    callback = null;
                } else {
                    callback = () -> {
                        // Set Modified
                        nmsChunk.setChanged(true);
                        if (finalizer != null) {
                            finalizer.run();
                        }
                    };
                }
                QueueHandler queueHandler = Fawe.instance().getQueueHandler();
                // Chain the sync tasks and the callback
                Callable<Future> chain = () -> {
                    try {
                        // Run the sync tasks
                        for (Runnable task : syncTasks) {
                            if (task != null) {
                                task.run();
                            }
                        }
                        if (callback == null) {
                            if (finalizer != null) {
                                finalizer.run();
                            }
                            return null;
                        } else {
                            return queueHandler.async(callback, null);
                        }
                    } catch (Throwable e) {
                        e.printStackTrace();
                        throw e;
                    }
                };
                //noinspection unchecked - required at compile time
                return (T) (Future) queueHandler.sync(chain);
            }
        } catch (Throwable e) {
            e.printStackTrace();
            return null;
        } finally {
            forceLoadSections = true;
        }
    }

    private void setSectionBiomes(final BiomeType[] biomes, final ChunkSection newSection) {
        for (int y = 0, index = 0; y < 4; y++) {
            for (int z = 0; z < 4; z++) {
                for (int x = 0; x < 4; x++, index++) {
                    BiomeType biomeType = biomes[index];
                    if (biomeType == null) {
                        continue;
                    }
                    for (int i = 0; i < 4; i++) {
                        newSection.setBiomeId(
                                x * 4 + i,
                                y * 4 + i,
                                z * 4 + i,
                                (byte) PNXAdapter.adapt(biomeType).getId()
                        );
                    }
                }
            }
        }
    }

    private char[] loadPrivately(int layer) {
        layer -= getMinSectionPosition();
        if (super.sections[layer] != null) {
            synchronized (super.sectionLocks[layer]) {
                if (super.sections[layer].isFull() && super.blocks[layer] != null) {
                    char[] blocks = new char[4096];
                    System.arraycopy(super.blocks[layer], 0, blocks, 0, 4096);
                    return blocks;
                }
            }
        }
        return this.update(layer, null, true);
    }

    @Override
    public char[] update(int layer, char[] data, boolean aggressive) {
        cn.nukkit.level.format.ChunkSection section = getSections(aggressive)[layer];
        // Section is null, return empty array
        if (section == null) {
            data = new char[4096];
            Arrays.fill(data, (char) BlockTypesCache.ReservedIDs.AIR);
            return data;
        }
        if (data != null && data.length != 4096) {
            data = new char[4096];
            Arrays.fill(data, (char) BlockTypesCache.ReservedIDs.AIR);
        }
        if (data == null || data == FaweCache.INSTANCE.EMPTY_CHAR_4096) {
            data = new char[4096];
            Arrays.fill(data, (char) BlockTypesCache.ReservedIDs.AIR);
        }

        try {
            sectionLock.readLock().lock();
            for (int y = 0, index = 0; y < 16; y++) {
                for (int z = 0; z < 16; z++) {
                    for (int x = 0; x < 16; x++, index++) {
                        var state = section.getBlockState(x, y, z);
                        if (state == cn.nukkit.blockstate.BlockState.AIR) {
                            data[index] = 0;
                        } else {
                            data[index] = PNXAdapter.adapt(section.getBlockState(x, y, z)).getOrdinalChar();
                        }
                    }
                }
            }
            return data;
        } finally {
            sectionLock.readLock().unlock();
        }
    }

    private void updateGet(
            cn.nukkit.level.format.anvil.Chunk nmsChunk,
            cn.nukkit.level.format.ChunkSection[] chunkSections,
            cn.nukkit.level.format.ChunkSection section,
            char[] arr,
            int layer
    ) {
        try {
            sectionLock.writeLock().lock();
            if (this.getChunk() != nmsChunk) {
                this.pnxChunk = nmsChunk;
                this.pnxChunkSections = new cn.nukkit.level.format.ChunkSection[chunkSections.length];
                System.arraycopy(chunkSections, 0, this.pnxChunkSections, 0, chunkSections.length);
                this.reset();
            }
            if (this.pnxChunkSections == null) {
                this.pnxChunkSections = new cn.nukkit.level.format.ChunkSection[chunkSections.length];
                System.arraycopy(chunkSections, 0, this.pnxChunkSections, 0, chunkSections.length);
            }
            if (this.pnxChunkSections[layer] != section) {
                // Not sure why it's funky, but it's what I did in commit fda7d00747abe97d7891b80ed8bb88d97e1c70d1 and I don't want to touch it >dords
                this.pnxChunkSections[layer] = new cn.nukkit.level.format.ChunkSection[]{section}.clone()[0];
            }
        } finally {
            sectionLock.writeLock().unlock();
        }
        this.blocks[layer] = arr;
    }

    public cn.nukkit.level.format.ChunkSection[] getSections(boolean force) {
        force &= forceLoadSections;
        sectionLock.readLock().lock();
        cn.nukkit.level.format.ChunkSection[] tmp = pnxChunkSections;
        sectionLock.readLock().unlock();
        if (tmp == null || force) {
            try {
                sectionLock.writeLock().lock();
                tmp = pnxChunkSections;
                if (tmp == null || force) {
                    cn.nukkit.level.format.ChunkSection[] chunkSections = ((Chunk) getChunk()).getSections();
                    tmp = new cn.nukkit.level.format.ChunkSection[chunkSections.length];
                    System.arraycopy(chunkSections, 0, tmp, 0, chunkSections.length);
                    pnxChunkSections = tmp;
                }
            } finally {
                sectionLock.writeLock().unlock();
            }
        }
        return tmp;
    }

    public cn.nukkit.level.format.anvil.Chunk getChunk() {
        cn.nukkit.level.format.anvil.Chunk levelChunk = this.pnxChunk;
        if (levelChunk == null) {
            synchronized (this) {
                levelChunk = this.pnxChunk;
                if (levelChunk == null) {
                    this.pnxChunk = levelChunk = ensureLoaded(this.serverLevel, chunkX, chunkZ);
                }
            }
        }
        return levelChunk;
    }

    private void fillLightNibble(char[][] light, LightLayer lightLayer, int minSectionPosition, int maxSectionPosition) {
        for (int Y = 0; Y <= maxSectionPosition - minSectionPosition; Y++) {
            if (light[Y] == null || this.pnxChunkSections[Y] == null) {
                continue;
            }
            if (this.pnxChunkSections[Y] instanceof EmptyChunkSection) {
                continue;
            }
            if (lightLayer == LightLayer.BLOCK) {
                synchronized (this.pnxChunkSections[Y]) {
                    for (int x = 0; x < 16; x++) {
                        for (int y = 0; y < 16; y++) {
                            for (int z = 0; z < 16; z++) {
                                int i = y << 8 | z << 4 | x;
                                this.pnxChunkSections[Y].setBlockLight(x, y, z, light[Y][i] & 15);
                            }
                        }
                    }
                }
            } else if (lightLayer == LightLayer.SKY) {
                synchronized (this.pnxChunkSections[Y]) {
                    for (int x = 0; x < 16; x++) {
                        for (int y = 0; y < 16; y++) {
                            for (int z = 0; z < 16; z++) {
                                int i = y << 8 | z << 4 | x;
                                this.pnxChunkSections[Y].setBlockSkyLight(x, y, z, light[Y][i] & 15);
                            }
                        }
                    }
                }
            }
        }
    }

    @Override
    public boolean hasSection(int layer) {
        layer -= getMinSectionPosition();
        return getSections(false)[layer] != null;
    }

    @Override
    public synchronized boolean trim(boolean aggressive) {
        if (aggressive) {
            sectionLock.writeLock().lock();
            pnxChunkSections = null;
            this.pnxChunk = null;
            sectionLock.writeLock().unlock();
            return super.trim(true);
        } else if (pnxChunkSections == null) {
            // don't bother trimming if there are no sections stored.
            return true;
        } else {
            for (int i = getMinSectionPosition(); i <= getMaxSectionPosition(); i++) {
                int layer = i - getMinSectionPosition();
                if (!hasSection(i) || !super.sections[layer].isFull()) {
                    continue;
                }
                cn.nukkit.level.format.ChunkSection existing = getSections(true)[layer];
                super.trim(false, i);
            }
            return true;
        }
    }

}
