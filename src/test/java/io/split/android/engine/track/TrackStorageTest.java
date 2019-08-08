package io.split.android.engine.track;

import com.google.gson.reflect.TypeToken;

import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import io.split.android.client.dtos.ChunkHeader;
import io.split.android.client.dtos.Event;
import io.split.android.client.dtos.KeyImpression;
import io.split.android.client.dtos.TestImpressions;
import io.split.android.client.impressions.StoredImpressions;
import io.split.android.client.storage.IStorage;
import io.split.android.client.storage.MemoryStorage;
import io.split.android.client.track.EventsChunk;
import io.split.android.client.track.TrackStorageManager;
import io.split.android.client.utils.Json;

public class TrackStorageTest {

    TrackStorageManager mTrackStorage = null;
    Set<String> mInitialChunkIds = null;

    Type chunkHeaderType = new TypeToken<List<ChunkHeader>>() {
    }.getType();
    Type eventsFileType = new TypeToken<Map<String, List<Event>>>() {
    }.getType();
    final String CHUNK_HEADERS_FILE_NAME = "SPLITIO.events_chunk_headers.json";
    final String EVENTS_FILE_NAME = "SPLITIO.events_#%d.json";
    final int MAX_FILE_SIZE = 1000000;

    @Before
    public void setupUp(){

        final String FILE_NAME = "SPLITIO.events.json";

        Map<String, EventsChunk> eventsChunks = new HashMap<>();
        IStorage memStorage = new MemoryStorage();
        mInitialChunkIds = new HashSet<>();
        final int CHUNK_COUNT = 4;
        for(int i = 0; i < CHUNK_COUNT; i++) {
            List<Event> events  = new ArrayList<>();
            for(int j = 0; j < 4; j++) {
                Event event = new Event();
                event.eventTypeId = String.format("type-%d-%d", i, j);
                event.key = String.format("key-%d-%d", i, j);
                events.add(event);
            }

            EventsChunk chunk = new EventsChunk(events);
            mInitialChunkIds.add(chunk.getId());
            eventsChunks.put(chunk.getId(), chunk);
        }
        try {
            String allChunks = Json.toJson(eventsChunks);
            memStorage.write(FILE_NAME, allChunks);
        } catch (IOException e) {
        }
        mTrackStorage = new TrackStorageManager(memStorage);
    }

    @Test
    public void getEventsChunks() {
        List<EventsChunk> chunks = mTrackStorage.getEventsChunks();
        List<String> chunkIds = new ArrayList<>(mInitialChunkIds);
        Assert.assertEquals(4, chunks.size());
        Assert.assertNotEquals(-1, getIndexForChunk(chunkIds.get(0), chunks));
        Assert.assertNotEquals(-1, getIndexForChunk(chunkIds.get(1), chunks));
        Assert.assertNotEquals(-1, getIndexForChunk(chunkIds.get(2), chunks));
        Assert.assertNotEquals(-1, getIndexForChunk(chunkIds.get(3), chunks));

        Assert.assertEquals(4, chunks.get(0).getEvents().size());
        Assert.assertEquals(4, chunks.get(3).getEvents().size());
    }

    @Test
    public void saveEvents(){

        List<Event> events  = new ArrayList<>();
        Event event = new Event();
        event.eventTypeId = "type-test";
        event.key = "key-test";
        events.add(event);

        EventsChunk chunk = new EventsChunk(events);
        String chunkIdAdded = chunk.getId();
        mTrackStorage.saveEvents(chunk);

        List<EventsChunk> chunks = mTrackStorage.getEventsChunks();
        int chunkAddedIndex = getIndexForChunk(chunkIdAdded, chunks);
        Assert.assertEquals(5, chunks.size());
        Assert.assertNotEquals(-1, chunkAddedIndex);

        List<Event> eventsAdded = chunks.get(chunkAddedIndex).getEvents();
        Assert.assertEquals(1, eventsAdded.size());
        Assert.assertEquals("type-test", eventsAdded.get(0).eventTypeId);
    }

    @Test
    public void deleteEventsChunk() {
        List<EventsChunk> chunks = mTrackStorage.getEventsChunks();
        String chunkIdToRemove = chunks.get(0).getId();
        mTrackStorage.deleteCachedEvents(chunkIdToRemove);
        chunks = mTrackStorage.getEventsChunks();
        Assert.assertEquals(3, chunks.size());
        Assert.assertEquals(-1, getIndexForChunk(chunkIdToRemove, chunks));
    }

    @Test
    public void testSaveAndLoadChunkFiles() throws IOException {

        int totalSize = 0;
        final int chunkCount = 10;
        IStorage memStorage = new MemoryStorage();
        int[][] chunksData = {
                {35, 4, 86, 40, 200, 120, 20, 420, 8, 911},
                {1100, 1305, 4506, 7530, 3209, 5230, 6500, 6880, 4100, 23000},
        };

        TrackStorageManager savingManager = new TrackStorageManager(memStorage);

        for(int i = 0; i < chunkCount; i++) {
            int eventCount = chunksData[0][i];
            int eventSize = chunksData[1][i];
            List<Event> events  = new ArrayList<>();
            for (int j = 0; j < eventCount; j++) {
                Event event = new Event();
                event.eventTypeId = String.format("type-%d-%d", i, j);
                event.key = String.format("key-%d-%d", i, j);
                event.setSizeInBytes(eventSize);
                events.add(event);
                totalSize += eventSize;
            }
            EventsChunk chunk = new EventsChunk(events);
            chunk.addAtempt();

            if(i % 3 == 2) {
                chunk.addAtempt();
            } else if(i % 3 == 1) {
                chunk.addAtempt();
            }
            savingManager.saveEvents(chunk);
        }

        List<EventsChunk> savedChunks = savingManager.getEventsChunks();

        savingManager.close(); // Close saves to disk

        TrackStorageManager loadingManager = new TrackStorageManager(memStorage);
        List<EventsChunk> loadedChunks = loadingManager.getEventsChunks();

        String headerContent = memStorage.read(CHUNK_HEADERS_FILE_NAME);
        List<ChunkHeader> headers = Json.fromJson(headerContent, chunkHeaderType);
        List<String> allEventFiles = memStorage.getAllIds("SPLITIO.events_#");
        List<Map<String, List<Event>>> events = new ArrayList<>();
        int expectedFileCount = totalSize / MAX_FILE_SIZE + (totalSize % MAX_FILE_SIZE > 0 ? 1 : 0);
        List<Integer> sizes = new ArrayList();
        for (int i = 0; i < expectedFileCount; i++) {
            String file = memStorage.read(String.format(EVENTS_FILE_NAME, i));
            Map<String, List<Event>> eventsFile = Json.fromJson(file, eventsFileType);
            events.add(eventsFile);
            sizes.add(new Integer(sizeInBytes(eventsFile)));
        }

        Assert.assertNotNull(headerContent);
        Assert.assertEquals(10, headers.size());
        Assert.assertEquals(expectedFileCount, allEventFiles.size()); // including headers file
        for (int i = 0; i < sizes.size(); i++) {
            Assert.assertTrue(sizes.get(i).intValue() <= MAX_FILE_SIZE);
        }

        Assert.assertEquals(10, loadedChunks.size());
        Assert.assertEquals(savedChunks.size(), loadedChunks.size());
        for(EventsChunk savedChunk : savedChunks) {
            EventsChunk loadedChunk = loadedChunks.get(getIndexForChunk(savedChunk.getId(), loadedChunks));
            Assert.assertEquals(savedChunk.getAttempt(), loadedChunk.getAttempt());
            Assert.assertEquals(savedChunk.getEvents().size(), loadedChunk.getEvents().size());
            Assert.assertEquals(sizeInBytes(savedChunk.getEvents()), sizeInBytes(loadedChunk.getEvents()));
        }
    }

    @Test
    public void testLoadLegacyFromLegacyFile() throws IOException {
        final String LEGACY_EVENTS_FILE_NAME = "SPLITIO.events.json";

        final int chunkCount = 10;
        IStorage memStorage = new MemoryStorage();
        int[][] chunksData = {
                {35, 4, 86, 40, 200, 120, 20, 420, 8, 911},
                {1100, 1305, 4506, 7530, 3209, 5230, 6500, 6880, 4100, 23000},
        };

        Map<String, EventsChunk> chunks = new HashMap<>();
        for(int i = 0; i < chunkCount; i++) {
            int eventCount = chunksData[0][i];
            int eventSize = chunksData[1][i];
            List<Event> events  = new ArrayList<>();
            for (int j = 0; j < eventCount; j++) {
                Event event = new Event();
                event.eventTypeId = String.format("type-%d-%d", i, j);
                event.key = String.format("key-%d-%d", i, j);
                event.setSizeInBytes(eventSize);
                events.add(event);
            }
            EventsChunk chunk = new EventsChunk("id_" + i, (i % 3));
            chunk.addEvents(events);
            chunks.put(chunk.getId(), chunk);
        }

        String jsonChunks = Json.toJson(chunks);
        memStorage.write(LEGACY_EVENTS_FILE_NAME, jsonChunks);

        TrackStorageManager manager = new TrackStorageManager(memStorage);
        List<EventsChunk> loadedChunks = manager.getEventsChunks();

        Assert.assertEquals(10, loadedChunks.size());
        for(int i = 0; i< 10; i++) {
            EventsChunk loadedChunk = loadedChunks.get(getIndexForChunk("id_" + i, loadedChunks));
            Assert.assertEquals((i % 3), loadedChunk.getAttempt());
            Assert.assertEquals(chunksData[0][i], loadedChunk.getEvents().size());
            Assert.assertEquals(chunksData[0][i] * chunksData[1][i], sizeInBytes(loadedChunk.getEvents()));
        }
    }

    @Test
    public void testMissingEventsFile() throws IOException {
        IStorage memStorage = new MemoryStorage();
        List<ChunkHeader> headers = new ArrayList<>();
        for(int i = 0; i < 3; i++) {
            ChunkHeader c = new ChunkHeader("c" + i, 0);
            headers.add(c);
        }
        String json = Json.toJson(headers);
        memStorage.write(CHUNK_HEADERS_FILE_NAME, json);

        TrackStorageManager manager = new TrackStorageManager(memStorage);

        Assert.assertNotNull(manager);

    }

    // Helpers
    private int getIndexForChunk(String chunkId, List<EventsChunk> chunks) {

        int index = -1;
        int i = 0;
        boolean found = false;

        while(!found && chunks.size() > i){
            String id = chunks.get(i).getId();
            if(chunkId.equals(id)){
                found = true;
                index = i;
            }
            i++;
        }
        return index;
    }

    private int sizeInBytes(Map<String, List<Event>> eventsPerChunk) {
        int sum = 0;
        for (List<Event> events : eventsPerChunk.values()) {
            sum += sizeInBytes(events);
        }
        return sum;
    }

    private int sizeInBytes(List<Event> events) {
        int sum = 0;
        for (Event event : events) {
            sum += event.getSizeInBytes();
        }
        return sum;
    }
}
