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
    private double accumulatedTime;  // マスターサーバー用の内部カウンター
    private Thread syncServerThread; // マスター用の同期サーバースレッド

    // スレーブ用の内部変数
    private double slaveAccumulatedTime;

    @Override
    public void onEnable() {
        // config.yml を初期化（config.yml もプロジェクト内に用意してください）
        saveDefaultConfig();
        dayLength   = getConfig().getInt("dayLength", 24000);
        isMaster    = getConfig().getBoolean("isMaster", true);
        syncInterval= getConfig().getInt("syncInterval", 30);
        syncPort    = getConfig().getInt("syncPort", 5000);
        syncHost    = getConfig().getString("syncHost", "localhost");

        // デフォルトのワールドを取得して、自然な時間進行を停止
        world = Bukkit.getWorlds().get(0);
        world.setGameRule(GameRule.DO_DAYLIGHT_CYCLE, false);
        accumulatedTime = world.getTime();

        if (isMaster) {
            startSyncServer();
            startMasterTimeTask();
        } else {
            // スレーブはまずマスターと同期してからローカルで時間を更新
            startSlaveSyncTask();
            startSlaveTimeTask();
        }
    }

    @Override
    public void onDisable() {
        if (isMaster && syncServerThread != null && syncServerThread.isAlive()) {
            syncServerThread.interrupt();
        }
    }

    // マスターサーバー：毎ティックごとに時間を更新するタスク
    private void startMasterTimeTask() {
        new BukkitRunnable() {
            @Override
            public void run() {
                // 通常のMinecraftの1日は24000ティックだけど、
                // 調整後の日数に合わせるための1ティックあたりの加算量を計算する
                double increment = 24000.0 / dayLength;
                accumulatedTime += increment;
                long timeToSet = (long) (accumulatedTime % 24000);
                world.setTime(timeToSet);
            }
        }.runTaskTimer(this, 1L, 1L);
    }

    // マスターサーバー：外部からの接続に対して現在の時間を返すTCPサーバーを開始
    private void startSyncServer() {
        syncServerThread = new Thread(() -> {
            try (ServerSocket serverSocket = new ServerSocket(syncPort)) {
                while (!Thread.currentThread().isInterrupted()) {
                    try (Socket clientSocket = serverSocket.accept();
                         PrintWriter out = new PrintWriter(clientSocket.getOutputStream(), true)) {
                        long currentTime = (long) (accumulatedTime % 24000);
                        out.println(currentTime);
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                }
            } catch (IOException e) {
                e.printStackTrace();
            }
        });
        syncServerThread.start();
    }

    // スレーブサーバー：マスターと同期するためのタスク（非同期で実行）
    private void startSlaveSyncTask() {
        new BukkitRunnable() {
            @Override
            public void run() {
                try (Socket socket = new Socket(syncHost, syncPort);
                     BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()))) {
                    String line = in.readLine();
                    if (line != null) {
                        long syncedTime = Long.parseLong(line.trim());
                        // 同期した時点の時間をローカルカウンターに反映
                        slaveAccumulatedTime = syncedTime;
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        }.runTaskTimerAsynchronously(this, 0L, syncInterval * 20L);  // syncInterval秒ごとに実行
    }

    // スレーブサーバー：同期後、ローカルで時間を進行させるタスク
    private void startSlaveTimeTask() {
        new BukkitRunnable() {
            @Override
            public void run() {
                double increment = 24000.0 / dayLength;
                slaveAccumulatedTime += increment;
                long timeToSet = (long) (slaveAccumulatedTime % 24000);
                world.setTime(timeToSet);
            }
        }.runTaskTimer(this, 1L, 1L);
    }
}