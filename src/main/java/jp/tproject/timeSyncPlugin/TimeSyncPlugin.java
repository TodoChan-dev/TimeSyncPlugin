package jp.tproject.timeSyncPlugin;

import org.bukkit.Bukkit;
import org.bukkit.GameRule;
import org.bukkit.World;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.ServerSocket;
import java.net.Socket;

public class TimeSyncPlugin extends JavaPlugin {

    // 設定値
    private int dayLength;         // 調整後の1日の長さ（例：24000）
    private boolean isMaster;      // マスターサーバーかどうか
    private int syncInterval;      // 同期する間隔（秒）
    private int syncPort;          // マスターが待ち受けるポート
    private String syncHost;       // スレーブが接続するマスターサーバーのホスト名

    private World world;
    private long currentTime;      // 現在のゲーム内時間
    private Thread syncServerThread; // マスター用の同期サーバースレッド

    @Override
    public void onEnable() {
        // config.yml を初期化
        saveDefaultConfig();
        dayLength   = getConfig().getInt("dayLength", 48000);
        isMaster    = getConfig().getBoolean("isMaster", true);
        syncInterval= getConfig().getInt("syncInterval", 30);
        syncPort    = getConfig().getInt("syncPort", 5000);
        syncHost    = getConfig().getString("syncHost", "localhost");

        // デフォルトのワールドを取得して、自然な時間進行を停止
        world = Bukkit.getWorlds().get(0);
        world.setGameRule(GameRule.DO_DAYLIGHT_CYCLE, false);
        currentTime = world.getTime();

        if (isMaster) {
            // マスターサーバーの場合は時間を管理し、同期サーバーを起動
            startSyncServer();
            startMasterTimeTask();
        } else {
            // スレーブサーバーの場合はマスターから時間を取得するだけ
            startSlaveSyncTask();
        }
    }

    @Override
    public void onDisable() {
        if (isMaster && syncServerThread != null && syncServerThread.isAlive()) {
            syncServerThread.interrupt();
        }
    }

    // マスターサーバー：2秒ごとに時間を更新するタスク
    private void startMasterTimeTask() {
        new BukkitRunnable() {
            @Override
            public void run() {
                // 通常の速度の半分になるように時間を進める
                // 通常のMinecraftでは1秒に20ティック進む
                // 2秒ごとに実行して20ティック進めると、通常の半分の速度になる
                currentTime = (currentTime + 20) % 24000;
                world.setTime(currentTime);

                // デバッグログ
                getLogger().info("Master time updated to: " + currentTime);
            }
        }.runTaskTimer(this, 40L, 40L); // 40 ticks = 2 seconds
    }

    // マスターサーバー：外部からの接続に対して現在の時間を返すTCPサーバーを開始
    private void startSyncServer() {
        syncServerThread = new Thread(() -> {
            try (ServerSocket serverSocket = new ServerSocket(syncPort)) {
                getLogger().info("Time sync server started on port " + syncPort);

                while (!Thread.currentThread().isInterrupted()) {
                    try (Socket clientSocket = serverSocket.accept();
                         PrintWriter out = new PrintWriter(clientSocket.getOutputStream(), true)) {

                        // 現在の時間を送信
                        out.println(currentTime);
                        getLogger().info("Sent time " + currentTime + " to slave at " +
                                clientSocket.getInetAddress().getHostAddress());
                    } catch (IOException e) {
                        getLogger().warning("Error in sync server: " + e.getMessage());
                    }
                }
            } catch (IOException e) {
                getLogger().severe("Failed to start sync server: " + e.getMessage());
            }
        });

        syncServerThread.setDaemon(true);
        syncServerThread.start();
    }

    // スレーブサーバー：マスターと同期するためのタスク
    private void startSlaveSyncTask() {
        new BukkitRunnable() {
            @Override
            public void run() {
                try (Socket socket = new Socket(syncHost, syncPort);
                     BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()))) {

                    String line = in.readLine();
                    if (line != null) {
                        try {
                            long syncedTime = Long.parseLong(line.trim());

                            // UIスレッドで時間を設定
                            new BukkitRunnable() {
                                @Override
                                public void run() {
                                    world.setTime(syncedTime);
                                    getLogger().info("Slave time synced to: " + syncedTime);
                                }
                            }.runTask(TimeSyncPlugin.this);
                        } catch (NumberFormatException e) {
                            getLogger().warning("Received invalid time from master: " + line);
                        }
                    }
                } catch (Exception e) {
                    getLogger().warning("Failed to sync with master: " + e.getMessage());
                }
            }
        }.runTaskTimerAsynchronously(this, 0L, syncInterval * 20L);  // syncInterval秒ごとに実行
    }
}