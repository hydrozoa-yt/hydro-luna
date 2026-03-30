package io.luna;

import com.google.common.collect.BiMap;
import com.google.common.collect.HashBiMap;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import io.luna.game.cache.Cache;
import io.luna.game.cache.codec.*;
import io.luna.game.model.def.NpcDefinition;

import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

public class NpcImporter {

    private class NpcData {
        protected int id = -1;
        protected int respawnRate = -1;
        protected String moverestrict = "";
        protected int wanderrange = -1;
    }

    private class NpcSpawn {
        protected int id;
        protected int x;
        protected int y;
        protected int height;
    }

    private BiMap<Integer, String> idNameMap = HashBiMap.create();
    private Map<Integer, NpcData> npcData = new HashMap<>();
    private List<NpcSpawn> npcSpawn = new ArrayList<>();

    private void importNpcs() {
        LunaContext ctx = new LunaContext();
        Cache cache = new Cache();
        try {
            cache.open();
            cache.runDecoders(ctx,
                    new ObjectDefinitionDecoder(),
                    new WidgetDefinitionDecoder(),
                    new ItemDefinitionDecoder(),
                    new NpcDefinitionDecoder(),
                    new VarBitDefinitionDecoder(),
                    new VarpDefinitionDecoder(),
                    new MapDecoder());
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

        Path npcPack = Paths.get("data/dumps/npc.pack");
        try {
            List<String> lines = Files.readAllLines(npcPack);
            for (String s : lines) {
                String[] tokens = s.split("=");
                idNameMap.put(Integer.parseInt(tokens[0]), tokens[1]);
            }
        } catch (IOException e) {
            e.printStackTrace();
            return;
        }

        Path startPath = Paths.get("data/dumps/areas/");
        try (Stream<Path> stream = Files.walk(startPath)) {
            stream.filter(f -> Files.isRegularFile(f) && f.getFileName().toString().endsWith(".npc"))
                    .forEach(f -> {
                        //System.out.println("Reading file: "+f.getFileName().toString());
                        try {
                            List<String> lines = Files.readAllLines(f);
                            NpcData dat = null;
                            for (String s : lines) {
                                s = s.trim();
                                if (s.startsWith("[") && s.endsWith("]")) {
                                    String name = s.replace("[", "").replace("]", "");
                                    dat = new NpcData();
                                    int id = idNameMap.inverse().get(name);
                                    dat.id = id;
                                    npcData.put(dat.id, dat);
                                    //System.out.println("Added "+name);
                                    continue;
                                }
                                if (s.trim().startsWith("moverestrict")) {
                                    dat.moverestrict = s.trim().split("=")[1];
                                    continue;
                                }
                                if (s.trim().startsWith("wanderrange")) {
                                    dat.wanderrange = Integer.parseInt(s.trim().split("=")[1]);
                                    continue;
                                }
                                if (s.trim().startsWith("respawnrate")) {
                                    System.out.println("found respawn rate mob="+dat.id);
                                    dat.respawnRate = Integer.parseInt(s.trim().split("=")[1]);
                                    System.out.println(dat.respawnRate);
                                    System.out.println(npcData.get(dat.id).respawnRate);
                                    continue;
                                }
                            }
                        } catch (IOException e) {
                            e.printStackTrace();
                            return;
                        }
                    });
        } catch (IOException e) {
            e.printStackTrace();
        }

        System.out.println("Loaded data for "+npcData.size()+" npcs.");

        startPath = Paths.get("data/dumps/maps/");
        try (Stream<Path> stream = Files.walk(startPath)) {
            stream.filter(f -> Files.isRegularFile(f) && f.getFileName().toString().endsWith(".jm2"))
                    .forEach(f -> {
                        String filename = f.getFileName().toString().replace(".jm2", "");
                        filename = filename.substring(1);

                        String[] tokens = filename.split("_");
                        int chunkX = Integer.parseInt(tokens[0]);
                        int chunkY = Integer.parseInt(tokens[1]);

                        try {
                            List<String> lines = Files.readAllLines(f);
                            boolean foundNpcTag = false;
                            for (String line : lines) {
                                if (line.startsWith("==== NPC")) {
                                    foundNpcTag = true;
                                    continue;
                                }
                                if (line.startsWith("==== OBJ")) {
                                    break;
                                }
                                if (line.isEmpty()) {
                                    continue;
                                }
                                if (foundNpcTag) {
                                    String[] tokens1 = line.trim().replace(":", "").split(" ");
                                    int height = Integer.parseInt(tokens1[0]);
                                    int localX = Integer.parseInt(tokens1[1]);
                                    int localY = Integer.parseInt(tokens1[2]);
                                    int id = Integer.parseInt(tokens1[3]);

                                    int coordX = chunkX * 64 + localX;
                                    int coordY = chunkY * 64 + localY;

                                    NpcSpawn spawn = new NpcSpawn();
                                    spawn.id=id;
                                    spawn.x=coordX;
                                    spawn.y=coordY;
                                    spawn.height=height;
                                    npcSpawn.add(spawn);
                                }
                            }
                        } catch (IOException e) {
                            e.printStackTrace();
                            return;
                        }
                    });
        } catch (IOException e) {
            e.printStackTrace();
            return;
        }

        JsonArray rootArray = new JsonArray();
        for (NpcSpawn spawn : npcSpawn) {
            boolean forceNoMove = false;
            String name = NpcDefinition.ALL.get(spawn.id).isPresent() ? NpcDefinition.ALL.get(spawn.id).get().getName() : "null";
            if (name.equalsIgnoreCase("Fishing spot")) {
                forceNoMove = true;
                System.out.println("Excluded fishing spot");
            }
            if (name.equalsIgnoreCase("Banker")) {
                forceNoMove = true;
                System.out.println("Excluded banker");
            }

            JsonObject npcSpawnJson = new JsonObject();
            npcSpawnJson.addProperty("id", spawn.id);

            JsonObject position = new JsonObject();
            position.addProperty("x", spawn.x);
            position.addProperty("y", spawn.y);
            position.addProperty("z", spawn.height);
            npcSpawnJson.add("position", position);

            if (npcData.get(spawn.id) != null) {
                NpcData data = npcData.get(spawn.id);
                if (data.respawnRate > 0) {
                    npcSpawnJson.addProperty("respawn_ticks", data.respawnRate);
                }
                if (data.wanderrange > 0 && !forceNoMove) {
                    JsonObject wander = new JsonObject();
                    wander.addProperty("radius", data.wanderrange);
                    wander.addProperty("frequency", "NORMAL");
                    npcSpawnJson.add("wander", wander);
                } else if (data.wanderrange == -1 && !forceNoMove) {
                    JsonObject wander = new JsonObject();
                    wander.addProperty("radius", 5);
                    wander.addProperty("frequency", "NORMAL");
                    npcSpawnJson.add("wander", wander);
                }
            } else if (!forceNoMove) {
                JsonObject wander = new JsonObject();
                wander.addProperty("radius", 5);
                wander.addProperty("frequency", "NORMAL");
                npcSpawnJson.add("wander", wander);
            }

            rootArray.add(npcSpawnJson);

            //System.out.println(spawn.id+" "+spawn.x+" "+spawn.y+" ");
        }

        // Write to file
        try (FileWriter writer = new FileWriter("data/dumps/npc_spawns.json.generated")) {
            Gson gson = new GsonBuilder().setPrettyPrinting().create();
            gson.toJson(rootArray, writer);
        } catch (IOException e) {
            e.printStackTrace();
        }

        System.exit(0);
    }

    public static void main(String[] args) {
        NpcImporter importer = new NpcImporter();
        importer.importNpcs();
    }
}
