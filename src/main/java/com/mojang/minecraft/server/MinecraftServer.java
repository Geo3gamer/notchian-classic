package com.mojang.minecraft.server;

import com.mojang.minecraft.level.Level;
import com.mojang.minecraft.level.LevelIO;
import com.mojang.minecraft.level.generator.LevelGenerator;
import com.mojang.minecraft.net.PacketType;
import com.mojang.minecraft.server.network.NetworkManager;
import com.mojang.minecraft.server.network.PlayerConnection;

import java.io.*;
import java.net.URLEncoder;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.logging.ConsoleHandler;
import java.util.logging.Handler;
import java.util.logging.Logger;

public class MinecraftServer implements Runnable {
    static Logger logger = Logger.getLogger("MinecraftServer");
    static DateFormat dateFormat = new SimpleDateFormat("HH:mm:ss");
    private final NetworkManager netManager;
    private final Map<PlayerConnection, Player> playerConnections = new HashMap<>();
    private final List<Player> users = new ArrayList<>();
    private final List<DisconnectEntry> disconnectedConnections = new ArrayList<>();
    private int maxPlayers;
    public Level level;
    private boolean isPublic = false;
    public String serverName;
    public String greeting;
    private int port;
    public boolean adminSlot;
    public String heartbeatServer;
    private final Player[] onlinePlayers;
    public PlayerList admins = new PlayerList("Admins", new File("admins.txt"));
    public PlayerList banned = new PlayerList("Banned", new File("banned.txt"));
    private final PlayerList ipBanned = new PlayerList("Banned (IP)", new File("banned-ip.txt"));
    public PlayerList players = new PlayerList("Players", new File("players.txt"));
    private final List v = new ArrayList();
    private final String salt = "" + new Random().nextLong();
    private String externalUrl = "";
    public PlayerHasher hasher = new PlayerHasher(this.salt);
    public boolean verifyNames = false;
    private boolean growTrees = false;
    private int maxConnections;

    public MinecraftServer() {
        logger.info("Starting Minecraft Server");
        logger.info("Hello from CraftBukkit");

        Properties serverProperties = new Properties();
        try {
            serverProperties.load(new FileReader("server.properties"));
        } catch (final Exception ex) {
            MinecraftServer.logger.warning("Failed to load server.properties!");
        }

        try {
            this.serverName = serverProperties.getProperty("server-name", "Minecraft Server");
            this.greeting = serverProperties.getProperty("motd", "Welcome to my Minecraft Server!");
            this.port = Integer.parseInt(serverProperties.getProperty("port", "25565"));
            this.maxPlayers = Integer.parseInt(serverProperties.getProperty("max-players", "16"));
            this.isPublic = Boolean.parseBoolean(serverProperties.getProperty("public", "true"));
            this.verifyNames = Boolean.parseBoolean(serverProperties.getProperty("verify-names", "true"));
            this.growTrees = Boolean.parseBoolean(serverProperties.getProperty("grow-trees", "false"));
            this.adminSlot = Boolean.parseBoolean(serverProperties.getProperty("admin-slot", "false"));
            this.heartbeatServer = serverProperties.getProperty("heartbeat-server", "http://www.minecraft.net/heartbeat.jsp");

            if (this.maxPlayers < 1) {
                this.maxPlayers = 1;
            }
            if (this.maxPlayers > 32) {
                this.maxPlayers = 32;
            }
            this.maxConnections = Integer.parseInt(serverProperties.getProperty("max-connections", "3"));
            serverProperties.setProperty("server-name", this.serverName);
            serverProperties.setProperty("motd", this.greeting);
            serverProperties.setProperty("max-players", "" + this.maxPlayers);
            serverProperties.setProperty("port", "" + this.port);
            serverProperties.setProperty("public", "" + this.isPublic);
            serverProperties.setProperty("verify-names", "" + this.verifyNames);
            serverProperties.setProperty("max-connections", "3");
            serverProperties.setProperty("grow-trees", "" + this.growTrees);
            serverProperties.setProperty("admin-slot", "" + this.adminSlot);
        } catch (final Exception ex2) {
            ex2.printStackTrace();
            MinecraftServer.logger.warning("server.properties is broken! Delete it or fix it!");
            System.exit(0);
        }
        if (!this.verifyNames) {
            MinecraftServer.logger.warning("######################### WARNING #########################");
            MinecraftServer.logger.warning("verify-names is set to false! This means that anyone who");
            MinecraftServer.logger.warning("connects to this server can choose any username he or she");
            MinecraftServer.logger.warning("wants! This includes impersonating an OP!");
            if (this.isPublic) {
                MinecraftServer.logger.warning("");
                MinecraftServer.logger.warning("AND SINCE THIS IS A PUBLIC SERVER, IT WILL HAPPEN TO YOU!");
                MinecraftServer.logger.warning("");
            }
            MinecraftServer.logger.warning("If you wish to fix this, edit server.properties, and change");
            MinecraftServer.logger.warning("verify-names to true.");
            MinecraftServer.logger.warning("###########################################################");
        }
        try {
            serverProperties.store(new FileWriter("server.properties"), "Minecraft server properties");
        } catch (final Exception ex3) {
            MinecraftServer.logger.warning("Failed to save server.properties!");
        }
        this.onlinePlayers = new Player[this.maxPlayers];
        this.netManager = new NetworkManager(this.port, this);
        new ServerLogThread(this).start();
    }

    public final void disconnect(final PlayerConnection connection) {
        final Player player = this.playerConnections.get(connection);
        if (player != null) {
            this.players.remove(player.nickname);
            MinecraftServer.logger.info(player + " disconnected");
            this.playerConnections.remove(player.connection);
            this.users.remove(player);
            if (player.playerID >= 0) {
                this.onlinePlayers[player.playerID] = null;
            }
            this.sendPacketToAll(PacketType.DESPAWN, player.playerID);
        }
    }

    private void addToDisconnectQueue(final PlayerConnection playerConnection) {
        this.disconnectedConnections.add(new DisconnectEntry(playerConnection, 100));
    }

    public final void addToDisconnectQueue(final Player player) {
        this.disconnectedConnections.add(new DisconnectEntry(player.connection, 100));
    }

    public static void closeConnection(final Player player) {
        player.connection.closeConnection();
    }

    public final void sendPacketToAll(final PacketType packet, final Object... args) {
        for (Player user : this.users) {
            try {
                user.sendPacket(packet, args);
            } catch (final Exception ex) {
                user.disconnect(ex);
            }
        }
    }

    public final void sendPacketToAllExcept(final Player player, final PacketType packet, final Object... args) {
        for (Player user : this.users) {
            if (user != player) {
                try {
                    user.sendPacket(packet, args);
                } catch (final Exception ex) {
                    user.disconnect(ex);
                }
            }
        }
    }

    @Override
    public void run() {
        MinecraftServer.logger.info("Now accepting input on " + this.port);
        final int n = 50000000;
        final int n2 = 500000000;
        try {
            long nanoTime = System.nanoTime();
            long nanoTime2 = System.nanoTime();
            int n3 = 0;
            while (true) {
                this.d();
                while (System.nanoTime() - nanoTime2 > n) {
                    nanoTime2 += n;
                    this.processDisconnections();
                    if (n3 % 1200 == 0) {
                        try {
                            new LevelIO(this);
                            LevelIO.saveLevel(this.level, new FileOutputStream("server_level.dat"));
                        } catch (final Exception obj) {
                            MinecraftServer.logger.severe("Failed to save the level! " + obj);
                        }
                        MinecraftServer.logger.info("Level saved! Load: " + this.users.size() + "/" + this.maxPlayers);
                    }
                    if (n3 % 900 == 0) {
                        final HashMap<String, Object> args = new HashMap<>();
                        args.put("name", this.serverName);
                        args.put("users", this.users.size());
                        args.put("max", this.maxPlayers - (this.adminSlot ? 1 : 0));
                        args.put("public", this.isPublic);
                        args.put("port", this.port);
                        args.put("salt", this.salt);
                        args.put("admin-slot", this.adminSlot);
                        args.put("version", 7);
                        args.put("software", "A minecraft server");
                        new HeartbeatThread(this, assembleHeartbeat(args)).start();
                    }
                    ++n3;
                }
                while (System.nanoTime() - nanoTime > n2) {
                    nanoTime += n2;
                    this.sendPacketToAll(PacketType.PING);
                }
                Thread.sleep(5L);
            }
        } catch (final Exception thrown) {
            MinecraftServer.logger.log(java.util.logging.Level.SEVERE, "Error in main loop, server shutting down!", thrown);
            thrown.printStackTrace();
        }
    }

    private static String assembleHeartbeat(Map<String, Object> args) {
        try {
            StringBuilder var1 = new StringBuilder();

            for (String var3 : args.keySet()) {
                if (!var1.toString().equals("")) {
                    var1.append("&");
                }

                var1.append(var3).append("=").append(URLEncoder.encode(args.get(var3).toString(), StandardCharsets.UTF_8));
            }

            return var1.toString();
        } catch (Exception var4) {
            var4.printStackTrace();
            throw new RuntimeException("Failed to assemble heartbeat! This is pretty fatal");
        }
    }

    private void processDisconnections() {
        for (final Player player : this.users) {
            try {
                player.a();
            } catch (final Exception ex) {
                player.disconnect(ex);
            }
        }
        this.level.tick();
        for (int i = 0; i < this.disconnectedConnections.size(); ++i) {
            final DisconnectEntry disconnectEntry = this.disconnectedConnections.get(i);
            this.disconnect(disconnectEntry.connection);
            try {
                final PlayerConnection connection = disconnectEntry.connection;
                try {
                    if (connection.packetBuffer.position() > 0) {
                        connection.packetBuffer.flip();
                        connection.channel.write(connection.packetBuffer);
                        connection.packetBuffer.compact();
                    }
                } catch (final IOException ignored) {
                }
                if (disconnectEntry.delay-- <= 0) {
                    try {
                        disconnectEntry.connection.closeConnection();
                    } catch (final Exception ignored) {
                    }
                    this.disconnectedConnections.remove(i--);
                }
            } catch (final Exception ex4) {
                try {
                    disconnectEntry.connection.closeConnection();
                } catch (final Exception ignored) {
                }
            }
        }
    }

    public final void logInfo(final String message) {
        MinecraftServer.logger.info(message);
    }

    public final void logFine(final String message) {
        MinecraftServer.logger.fine(message);
    }

    private void d() {
        synchronized (this.v) {
            while (this.v.size() > 0) {
                this.handleCommand(null, (String) this.v.remove(0));
            }
        }
        try {
            final NetworkManager netManager = this.netManager;
            SocketChannel accept;
            while ((accept = netManager.channel.accept()) != null) {
                try {
                    accept.configureBlocking(false);
                    final PlayerConnection playerConnection = new PlayerConnection(accept);
                    netManager.connections.add(playerConnection);
                    final MinecraftServer server = netManager.server;
                    if (server.ipBanned.contains(playerConnection.address)) {
                        playerConnection.sendPacket(PacketType.DISCONNECT, "You're banned!");
                        MinecraftServer.logger.info(playerConnection.address + " tried to connect, but is banned.");
                        server.addToDisconnectQueue(playerConnection);
                        continue;
                    }
                    int i = 0;
                    for (Player user : server.users) {
                        if (user.connection.address.equals(playerConnection.address)) {
                            ++i;
                        }
                    }
                    if (i >= server.maxConnections) {
                        playerConnection.sendPacket(PacketType.DISCONNECT, "Too many connection!");
                        MinecraftServer.logger.info(playerConnection.address + " tried to connect, but is already connected " + i + " times.");
                        server.addToDisconnectQueue(playerConnection);
                        continue;
                    }
                    final int e;
                    if ((e = server.e()) < 0) {
                        playerConnection.sendPacket(PacketType.DISCONNECT, "The server is full!");
                        MinecraftServer.logger.info(playerConnection.address + " tried to connect, but failed because the server was full.");
                        server.addToDisconnectQueue(playerConnection);
                        continue;
                    }
                    final Player obj = new Player(server, playerConnection, e);
                    MinecraftServer.logger.info(obj + " connected");
                    server.playerConnections.put(playerConnection, obj);
                    server.users.add(obj);
                    if (obj.playerID < 0) {
                        continue;
                    }
                    server.onlinePlayers[obj.playerID] = obj;
                } catch (final IOException ex) {
                    accept.close();
                    throw ex;
                }
            }
            for (int j = 0; j < netManager.connections.size(); ++j) {
                final PlayerConnection connection = (PlayerConnection) netManager.connections.get(j);
                try {
                    final PlayerConnection playerConnection3;
                    (playerConnection3 = connection).channel.read(playerConnection3.c);
                    int n = 0;
                    while (playerConnection3.c.position() > 0 && n++ != 100) {
                        playerConnection3.c.flip();
                        final byte value = playerConnection3.c.get(0);
                        final PacketType packetType;
                        if ((packetType = PacketType.packets[value]) == null) {
                            throw new IOException("Bad command: " + value);
                        }
                        if (playerConnection3.c.remaining() < packetType.length + 1) {
                            playerConnection3.c.compact();
                            break;
                        }
                        playerConnection3.c.get();
                        final Object[] array = new Object[packetType.params.length];
                        for (int k = 0; k < array.length; ++k) {
                            array[k] = playerConnection3.get(packetType.params[k]);
                        }
                        playerConnection3.player.handlePacket(packetType, array);
                        if (!playerConnection3.connected) {
                            break;
                        }
                        playerConnection3.c.compact();
                    }
                    if (playerConnection3.packetBuffer.position() > 0) {
                        playerConnection3.packetBuffer.flip();
                        playerConnection3.channel.write(playerConnection3.packetBuffer);
                        playerConnection3.packetBuffer.compact();
                    }
                } catch (final Exception ex2) {
                    final MinecraftServer server2 = netManager.server;
                    final Player player;
                    if ((player = server2.playerConnections.get(connection)) != null) {
                        player.disconnect(ex2);
                    }
                }
                try {
                    if (!connection.connected) {
                        connection.closeConnection();
                        netManager.server.disconnect(connection);
                        netManager.connections.remove(j--);
                    }
                } catch (final Exception ex4) {
                    ex4.printStackTrace();
                }
            }
        } catch (final IOException cause) {
            throw new RuntimeException("IOException while ticking socketserver", cause);
        }
    }

    public final void handleCommand(final Player player, String command) {
        while (command.startsWith("/")) {
            command = command.substring(1);
        }
        MinecraftServer.logger.info(((player == null) ? "[console]" : player.nickname) + " admins: " + command);
        final String[] split;
        if ((split = command.split(" "))[0].equalsIgnoreCase("ban") && split.length > 1) {
            this.ban(split[1]);
            return;
        }
        if (split[0].equalsIgnoreCase("kick") && split.length > 1) {
            this.kick(split[1]);
            return;
        }
        if (split[0].equalsIgnoreCase("banip") && split.length > 1) {
            this.banIp(split[1]);
            return;
        }
        if (split[0].equalsIgnoreCase("unban") && split.length > 1) {
            final MinecraftServer minecraftServer = this;
            final String s = split[1];
            minecraftServer.banned.remove(s);
            return;
        }
        if (split[0].equalsIgnoreCase("op") && split.length > 1) {
            this.op(split[1]);
            return;
        }
        if (split[0].equalsIgnoreCase("deop") && split.length > 1) {
            this.deop(split[1]);
            return;
        }
        if (!split[0].equalsIgnoreCase("setspawn")) {
            if (split[0].equalsIgnoreCase("solid")) {
                if (player != null) {
                    player.isOp = !player.isOp;
                    if (player.isOp) {
                        player.sendMessage("Now placing unbreakable stone");
                        return;
                    }
                    player.sendMessage("Now placing normal stone");
                }
            } else {
                if (split[0].equalsIgnoreCase("broadcast") && split.length > 1) {
                    this.sendPacketToAll(PacketType.MESSAGE, -1, command.substring("broadcast ".length()).trim());
                    return;
                }
                if (split[0].equalsIgnoreCase("say") && split.length > 1) {
                    this.sendPacketToAll(PacketType.MESSAGE, -1, command.substring("say ".length()).trim());
                    return;
                }
                if ((split[0].equalsIgnoreCase("teleport") || split[0].equalsIgnoreCase("tp")) && split.length > 1) {
                    if (player == null) {
                        MinecraftServer.logger.info("Can't teleport from console!");
                        return;
                    }
                    final Player matchPlayer;
                    if ((matchPlayer = this.matchPlayer(split[1])) == null) {
                        player.sendPacket(PacketType.MESSAGE, -1, "No such player");
                        return;
                    }
                    player.connection.sendPacket(PacketType.CLIENT_POSITION_ORIENTATION, -1, matchPlayer.x, matchPlayer.y, matchPlayer.z, matchPlayer.yRot, matchPlayer.xRot);
                } else if (player != null) {
                    player.sendPacket(PacketType.MESSAGE, -1, "Unknown command!");
                }
            }
            return;
        }
        if (player != null) {
            this.level.setSpawnPos(player.x / 32, player.y / 32, player.z / 32, (float) (player.yRot * 320 / 256));
            return;
        }
        MinecraftServer.logger.info("Can't set spawn from console!");
    }

    public final void finalizeLevel(final int x, final int y, final int z) {
        this.sendPacketToAll(PacketType.SERVER_SET_BLOCK, x, y, z, this.level.getTile(x, y, z));
    }

    public final int getFreeSlots() {
        int n = 0;
        for (int i = 0; i < this.maxPlayers; ++i) {
            if (this.onlinePlayers[i] == null) {
                ++n;
            }
        }
        return n;
    }

    private int e() {
        for (int i = 0; i < this.maxPlayers; ++i) {
            if (this.onlinePlayers[i] == null) {
                return i;
            }
        }
        return -1;
    }

    public final List<Player> users() {
        return this.users;
    }

    private void kick(final String nickname) {
        boolean b = false;
        for (Player user : this.users) {
            final Player player;
            if ((player = user).nickname.equalsIgnoreCase(nickname)) {
                b = true;
                player.kick("You were kicked");
            }
        }
        if (b) {
            this.sendPacketToAll(PacketType.MESSAGE, -1, nickname + " got kicked from the server!");
        }
    }

    private void ban(final String nickname) {
        this.banned.add(nickname);
        boolean b = false;
        for (Player user : this.users) {
            final Player player;
            if ((player = user).nickname.equalsIgnoreCase(nickname)) {
                b = true;
                player.kick("You were banned");
            }
        }
        if (b) {
            this.sendPacketToAll(PacketType.MESSAGE, -1, nickname + " got banned!");
        }
    }

    private void op(final String nickname) {
        this.admins.add(nickname);
        for (Player user : this.users) {
            final Player player;
            if ((player = user).nickname.equalsIgnoreCase(nickname)) {
                player.sendMessage("You're now op!");
                player.sendPacket(PacketType.UPDATE_USER_TYPE, 100);
            }
        }
    }

    private void deop(final String nickname) {
        this.admins.remove(nickname);
        for (Player user : this.users) {
            final Player player;
            if ((player = user).nickname.equalsIgnoreCase(nickname)) {
                player.isOp = false;
                player.sendMessage("You're no longer op!");
                player.sendPacket(PacketType.UPDATE_USER_TYPE, 0);
            }
        }
    }

    private void banIp(final String criteria) {
        boolean b = false;
        StringBuilder str = new StringBuilder();
        for (Player user : this.users) {
            final Player player;
            if ((player = user).nickname.equalsIgnoreCase(criteria) || player.connection.address.equalsIgnoreCase(criteria) || player.connection.address.equalsIgnoreCase("/" + criteria)) {
                this.ipBanned.add(player.connection.address);
                player.kick("You were banned");
                if (str.toString().equals("")) {
                    str.append(", ");
                }
                str.append(player.nickname);
                b = true;
            }
        }
        if (b) {
            this.sendPacketToAll(PacketType.MESSAGE, -1, str + " got ip banned!");
        }
    }

    public final Player matchPlayer(final String nickname) {
        for (Player user : this.users) {
            if (user.nickname.equalsIgnoreCase(nickname)) {
                return user;
            }
        }
        return null;
    }

    public static void main(String[] args) {
        try {
            final MinecraftServer minecraftServer = new MinecraftServer();

            MinecraftServer.logger.info("Setting up");

            final File file = new File("server_level.dat");
            if (file.exists()) {
                try {
                    minecraftServer.level = new LevelIO(minecraftServer).loadLevel(new FileInputStream(file));
                } catch (final Exception ex) {
                    MinecraftServer.logger.warning("Failed to load level. Generating a new level");
                    ex.printStackTrace();
                }
            } else {
                MinecraftServer.logger.warning("No level file found. Generating a new level");
            }

            if (minecraftServer.level == null) {
                minecraftServer.level = new LevelGenerator(minecraftServer).generate("--", 256, 256, 64);
            }

            try {
                new LevelIO(minecraftServer);
                LevelIO.saveLevel(minecraftServer.level, new FileOutputStream("server_level.dat"));
            } catch (final Exception ignored) {
            }

            minecraftServer.level.creativeMode = true;
            minecraftServer.level.growTrees = minecraftServer.growTrees;
            minecraftServer.level.addListener$74652038(minecraftServer);

            new Thread(minecraftServer).start();
        } catch (final Exception ex3) {
            MinecraftServer.logger.severe("Failed to start the server!");
            ex3.printStackTrace();
        }
    }

    static /* synthetic */ List a(final MinecraftServer minecraftServer) {
        return minecraftServer.v;
    }

    static /* synthetic */ String getExternalUrl(final MinecraftServer minecraftServer) {
        return minecraftServer.externalUrl;
    }

    static /* synthetic */ String setExternalUrl(final MinecraftServer minecraftServer, final String string) {
        return minecraftServer.externalUrl = string;
    }

    static {
        MinecraftServer.logger = Logger.getLogger("MinecraftServer");
        MinecraftServer.dateFormat = new SimpleDateFormat("HH:mm:ss");
        final ServerLogFormatter serverLogFormatter = new ServerLogFormatter();
        Handler[] handlers;
        for (int length = (handlers = MinecraftServer.logger.getParent().getHandlers()).length, i = 0; i < length; ++i) {
            MinecraftServer.logger.getParent().removeHandler(handlers[i]);
        }
        final Handler handler;
        (handler = new ConsoleHandler()).setFormatter(serverLogFormatter);
        MinecraftServer.logger.addHandler(handler);
        try {
            final ServerLogStreamHandler handler2;
            (handler2 = new ServerLogStreamHandler(new FileOutputStream("server.log"), serverLogFormatter)).setFormatter(serverLogFormatter);
            MinecraftServer.logger.addHandler(handler2);
        } catch (final Exception obj) {
            MinecraftServer.logger.warning("Failed to open file server.log for writing: " + obj);
        }
    }
}
