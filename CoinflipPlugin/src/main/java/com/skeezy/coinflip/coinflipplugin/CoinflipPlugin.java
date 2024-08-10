package com.skeezy.coinflip.coinflipplugin;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

import net.milkbowl.vault.chat.Chat;
import net.milkbowl.vault.economy.Economy;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.Sound;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;

public class CoinflipPlugin extends JavaPlugin implements Listener {
    private Map<UUID, CoinflipBet> activeBets = new ConcurrentHashMap<>();
    private Map<UUID, PlayerStats> playerStats = new ConcurrentHashMap<>();
    private Map<UUID, Long> pendingBets = new ConcurrentHashMap<>();
    private List<CoinflipHistory> coinflipHistory = new ArrayList<>();
    private long minimumBet;
    private String guiTitle;
    private String chooseSideTitle;
    private String inProgressTitle;
    private Material headsItem;
    private Material tailsItem;
    private short headsData;
    private short tailsData;
    private int animationTicks;
    private int switchInterval;
    private Sound switchSound;
    private float switchSoundVolume;
    private float switchSoundPitch;
    private Sound winSound;
    private float winSoundVolume;
    private float winSoundPitch;
    private Gson gson;
    private File statsFile;
    private List<String> infoLore;
    private Economy economy;
    private double taxRate = 0.05;

    public void onEnable() {
        saveDefaultConfig();
        loadConfig();
        getServer().getPluginManager().registerEvents(this, (Plugin)this);
        this.gson = (new GsonBuilder()).setPrettyPrinting().create();
        this.statsFile = new File(getDataFolder(), "player_stats.json");
        loadPlayerStats();
        if (!setupEconomy()) {
            getLogger().severe("Vault not found! Disabling plugin.");
            getServer().getPluginManager().disablePlugin((Plugin)this);
            return;
        }
        (new BukkitRunnable() {
            public void run() {
                for (Player player : Bukkit.getOnlinePlayers()) {
                    if (player.getOpenInventory().getTitle().equals(CoinflipPlugin.this.guiTitle))
                        CoinflipPlugin.this.openCoinflipGUI(player);
                }
            }
        }).runTaskTimer((Plugin)this, 20L, 20L);
    }

    public void onDisable() {
        savePlayerStats();
    }

    private boolean setupEconomy() {
        RegisteredServiceProvider<Economy> economyProvider = getServer().getServicesManager().getRegistration(Economy.class);
        if (economyProvider != null)
            this.economy = (Economy)economyProvider.getProvider();
        return (this.economy != null);
    }

    private void loadConfig() {
        FileConfiguration config = getConfig();
        this.minimumBet = config.getLong("minimum-bet", 7500L);
        this.guiTitle = ChatColor.translateAlternateColorCodes('&', config.getString("gui-title", "&6Coinflip Bets"));
        this.chooseSideTitle = ChatColor.translateAlternateColorCodes('&', config.getString("choose-side-title", "&6Choose Side"));
        this.inProgressTitle = ChatColor.translateAlternateColorCodes('&', config.getString("in-progress-title", "&6Coinflip in Progress"));
        this.headsItem = Material.valueOf(config.getString("heads-item", "DIAMOND"));
        this.tailsItem = Material.valueOf(config.getString("tails-item", "EMERALD"));
        this.headsData = (short)config.getInt("heads-data", 0);
        this.tailsData = (short)config.getInt("tails-data", 0);
        this.animationTicks = config.getInt("animation-ticks", 160);
        this.switchInterval = config.getInt("switch-interval", 5);
        this.switchSound = Sound.valueOf(config.getString("switch-sound", "CLICK"));
        this.switchSoundVolume = (float)config.getDouble("switch-sound-volume", 1.0D);
        this.switchSoundPitch = (float)config.getDouble("switch-sound-pitch", 1.0D);
        this.winSound = Sound.valueOf(config.getString("win-sound", "LEVEL_UP"));
        this.winSoundVolume = (float)config.getDouble("win-sound-volume", 1.0D);
        this.winSoundPitch = (float)config.getDouble("win-sound-pitch", 1.0D);
        this.infoLore = config.getStringList("InfoLore").stream()
                .map(line -> ChatColor.translateAlternateColorCodes('&', line))
                .collect(Collectors.toList());
    }

    private void loadPlayerStats() {
        if (this.statsFile.exists())
            try (FileReader reader = new FileReader(this.statsFile)) {
                PlayerStats[] stats = (PlayerStats[])this.gson.fromJson(reader, PlayerStats[].class);
                for (PlayerStats stat : stats)
                    this.playerStats.put(stat.getPlayerId(), stat);
            } catch (IOException e) {
                getLogger().warning("Failed to load player stats: " + e.getMessage());
            }
    }

    private void savePlayerStats() {
        try (FileWriter writer = new FileWriter(this.statsFile)) {
            this.gson.toJson(this.playerStats.values(), writer);
        } catch (IOException e) {
            getLogger().warning("Failed to save player stats: " + e.getMessage());
        }
    }

    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player)) {
            sender.sendMessage(ChatColor.RED + "Only players can use this command.");
            return true;
        }
        Player player = (Player)sender;
        if (command.getName().equalsIgnoreCase("coinflip")) {
            if (args.length == 0) {
                openCoinflipGUI(player);
            } else if (args.length == 1) {
                if (args[0].equalsIgnoreCase("cancel")) {
                    cancelCoinflip(player);
                } else if (args[0].equalsIgnoreCase("history")) {
                    openHistoryGUI(player);
                } else if (args[0].equalsIgnoreCase("reload")) {
                    if (!player.hasPermission("coinflip.reload")) {
                        player.sendMessage(ChatColor.RED + "You don't have permission to use this command.");
                    } else {
                        reloadConfig();
                        loadConfig();
                        player.sendMessage(ChatColor.translateAlternateColorCodes('&', "&b&lCOINFLIP &8» &aConfiguration reloaded."));
                    }
                } else {
                    long amount = parseAmount(args[0]);
                    if (amount < this.minimumBet) {
                        player.sendMessage(ChatColor.RED + "The minimum bet is " + formatAmount(this.minimumBet) + ".");
                        return true;
                    }
                    if (!this.economy.has((OfflinePlayer)player, amount)) {
                        player.sendMessage(ChatColor.RED + "You don't have enough money to place this bet.");
                        return true;
                    }
                    this.pendingBets.put(player.getUniqueId(), Long.valueOf(amount));
                    openBetGUI(player, amount);
                }
            }
            return true;
        }
        return false;
    }

    private void cancelCoinflip(Player player) {
        UUID playerId = player.getUniqueId();
        if (this.activeBets.containsKey(playerId)) {
            this.activeBets.remove(playerId);
            player.sendMessage(ChatColor.GREEN + "Your coinflip bet has been cancelled.");
        } else {
            player.sendMessage(ChatColor.RED + "You don't have an active coinflip bet to cancel.");
        }
    }

    private void openCoinflipGUI(Player player) {
        int size = Math.min(54, (this.activeBets.size() / 7 + 1) * 9);
        Inventory gui = Bukkit.createInventory(null, size, this.guiTitle);
        ItemStack glassPane = createItem(Material.STAINED_GLASS_PANE, (short)7, " ");
        for (int i = 0; i < size; i++)
            gui.setItem(i, glassPane);
        ItemStack infoPane = createItem(Material.STAINED_GLASS_PANE, (short)0, ChatColor.BLUE + "" + ChatColor.BOLD + "COINFLIP INFO", this.infoLore);
        gui.setItem(size - 1, infoPane);
        int slot = 0;
        for (CoinflipBet bet : this.activeBets.values()) {
            if (slot >= size - 1)
                break;
            gui.setItem(slot, createPlayerHead(bet.getPlayer(), bet.getAmount(), bet.getSide()));
            slot++;
        }
        player.openInventory(gui);
    }

    private void openBetGUI(Player player, long amount) {
        Inventory gui = Bukkit.createInventory(null, 9, this.chooseSideTitle);
        ItemStack glassPane = createItem(Material.STAINED_GLASS_PANE, (short)7, " ");
        for (int i = 0; i < 9; i++)
            gui.setItem(i, glassPane);
        ItemStack heads = createItem(this.headsItem, this.headsData, ChatColor.translateAlternateColorCodes('&', "&6Heads"),
                Arrays.asList(new String[] { ChatColor.GRAY + "Wager: " + ChatColor.GREEN + formatAmount(amount) }));
        ItemStack tails = createItem(this.tailsItem, this.tailsData, ChatColor.translateAlternateColorCodes('&', "&6Tails"),
                Arrays.asList(new String[] { ChatColor.GRAY + "Wager: " + ChatColor.GREEN + formatAmount(amount) }));
        gui.setItem(3, heads);
        gui.setItem(5, tails);
        player.openInventory(gui);
    }

    private void openHistoryGUI(Player player) {
        Inventory gui = Bukkit.createInventory(null, 54, ChatColor.translateAlternateColorCodes('&', "&7Coinflip History"));
        ItemStack background = createItem(Material.STAINED_GLASS_PANE, (short)15, " ");
        for (int i = 0; i < 54; i++)
            gui.setItem(i, background);
        int slot = 0;
        for (int j = this.coinflipHistory.size() - 1; j >= 0 && slot < 45; j--) {
            CoinflipHistory history = this.coinflipHistory.get(j);
            ItemStack item = createHistoryItem(history);
            gui.setItem(slot, item);
            slot++;
        }
        ItemStack refresh = createItem(Material.NETHER_STAR, (short)0, ChatColor.translateAlternateColorCodes('&', "&a&lREFRESH"));
        gui.setItem(49, refresh);
        player.openInventory(gui);
    }

    private ItemStack createHistoryItem(CoinflipHistory history) {
        Material material = history.getWinnerSide().equalsIgnoreCase("Heads") ? this.headsItem : this.tailsItem;
        short data = history.getWinnerSide().equalsIgnoreCase("Heads") ? this.headsData : this.tailsData;
        ItemStack item = createItem(material, data, ChatColor.translateAlternateColorCodes('&', "&a" + history.getWinner() + " &7defeated &c" + history.getLoser() + "&7."));
        ItemMeta meta = item.getItemMeta();
        List<String> lore = new ArrayList<>();
        lore.add(ChatColor.translateAlternateColorCodes('&', "&7Amount: &a&l" + formatAmount(history.getAmount()) + "&7."));
        lore.add(ChatColor.translateAlternateColorCodes('&', "&7Winner Side: &a&l" + history.getWinnerSide() + "&7."));
        lore.add(ChatColor.translateAlternateColorCodes('&', "&7"));
        lore.add(ChatColor.translateAlternateColorCodes('&', "&c&l" + history.getFormattedTime() + "&7."));
        meta.setLore(lore);
        item.setItemMeta(meta);
        return item;
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player))
            return;
        Player player = (Player)event.getWhoClicked();
        ItemStack clickedItem = event.getCurrentItem();
        if (event.getView().getTitle().equals(this.guiTitle) || event
                .getView().getTitle().equals(this.chooseSideTitle) || event
                .getView().getTitle().equals(this.inProgressTitle) || event
                .getView().getTitle().equals(ChatColor.translateAlternateColorCodes('&', "&7Coinflip History")))
            event.setCancelled(true);
        if (clickedItem == null || clickedItem.getType() == Material.AIR)
            return;
        if (event.getInventory().getTitle().equals(this.guiTitle)) {
            if (clickedItem.getType() == Material.SKULL_ITEM)
                handleBetAcceptance(player, clickedItem);
        } else if (event.getInventory().getTitle().equals(this.chooseSideTitle)) {
            if (clickedItem.getType() == this.headsItem || clickedItem.getType() == this.tailsItem) {
                String side = (clickedItem.getType() == this.headsItem) ? "Heads" : "Tails";
                Long amount = this.pendingBets.remove(player.getUniqueId());
                if (amount != null) {
                    createBet(player, amount.longValue(), side);
                    player.closeInventory();
                }
            }
        } else if (event.getView().getTitle().equals(ChatColor.translateAlternateColorCodes('&', "&7Coinflip History"))) {
            if (clickedItem.getType() == Material.NETHER_STAR) {
                openHistoryGUI(player);
            }
        }
    }
    @EventHandler
    public void onInventoryDrag(InventoryDragEvent event) {
        if (event.getView().getTitle().equals(this.guiTitle) || event
                .getView().getTitle().equals(this.chooseSideTitle) || event
                .getView().getTitle().equals(this.inProgressTitle) || event
                .getView().getTitle().equals(ChatColor.translateAlternateColorCodes('&', "&7Coinflip History")))
            event.setCancelled(true);
    }

    private void handleBetAcceptance(Player player, ItemStack betItem) {
        SkullMeta meta = (SkullMeta)betItem.getItemMeta();
        String betterName = meta.getOwner();
        Player better = Bukkit.getPlayer(betterName);
        if (better == null || !this.activeBets.containsKey(better.getUniqueId())) {
            player.sendMessage(ChatColor.RED + "This bet is no longer available.");
            return;
        }
        CoinflipBet bet = this.activeBets.get(better.getUniqueId());
        if (this.activeBets.remove(better.getUniqueId()) == null) {
            player.sendMessage(ChatColor.RED + "This bet has already been taken by someone else.");
            return;
        }
        if (!this.economy.has((OfflinePlayer)player, bet.getAmount())) {
            player.sendMessage(ChatColor.RED + "You don't have enough money to accept this bet.");
            this.activeBets.put(better.getUniqueId(), bet);
            return;
        }
        startCoinflip(player, better, bet);
    }

    private void startCoinflip(final Player challenger, final Player better, final CoinflipBet bet) {
        final Inventory gui = Bukkit.createInventory(null, 9, this.inProgressTitle);
        ItemStack glassPane = createItem(Material.STAINED_GLASS_PANE, (short)7, " ");
        for (int i = 0; i < 9; i++)
            gui.setItem(i, glassPane);
        gui.setItem(0, createPlayerHead(challenger, bet.getAmount(), bet.getSide().equals("Heads") ? "Tails" : "Heads"));
        gui.setItem(8, createPlayerHead(better, bet.getAmount(), bet.getSide()));
        gui.setItem(1, createItem(bet.getSide().equals("Heads") ? this.tailsItem : this.headsItem, bet.getSide().equals("Heads") ? this.tailsData : this.headsData, bet.getSide().equals("Heads") ? "Tails" : "Heads"));
        gui.setItem(7, createItem(bet.getSide().equals("Heads") ? this.headsItem : this.tailsItem, bet.getSide().equals("Heads") ? this.headsData : this.tailsData, bet.getSide()));
        challenger.openInventory(gui);
        better.openInventory(gui);
        (new BukkitRunnable() {
            int ticks = 0;
            int countdownTicks = 60;

            public void run() {
                if (this.ticks < this.countdownTicks) {
                    ItemStack countdownItem;
                    int secondsLeft = (this.countdownTicks - this.ticks) / 20;
                    switch (secondsLeft) {
                        case 3:
                            countdownItem = CoinflipPlugin.this.createItem(Material.STAINED_GLASS_PANE, (short)14, ChatColor.RED + "" + ChatColor.BOLD + "3");
                            break;
                        case 2:
                            countdownItem = CoinflipPlugin.this.createItem(Material.STAINED_GLASS_PANE, (short)4, ChatColor.YELLOW + "" + ChatColor.BOLD + "2");
                            break;
                        case 1:
                            countdownItem = CoinflipPlugin.this.createItem(Material.STAINED_GLASS_PANE, (short)5, ChatColor.GREEN + "" + ChatColor.BOLD + "1");
                            break;
                        default:
                            countdownItem = CoinflipPlugin.this.createItem(Material.STAINED_GLASS_PANE, (short)7, ChatColor.GRAY + "" + ChatColor.BOLD + "FLIPPING");
                            break;
                    }
                    gui.setItem(4, countdownItem);
                } else {
                    if (this.ticks >= this.countdownTicks + CoinflipPlugin.this.animationTicks) {
                        String winner = (Math.random() < 0.5D) ? "Heads" : "Tails";
                        ItemStack result = winner.equals("Heads") ? CoinflipPlugin.this.createItem(CoinflipPlugin.this.headsItem, CoinflipPlugin.this.headsData, ChatColor.AQUA + "Heads") : CoinflipPlugin.this.createItem(CoinflipPlugin.this.tailsItem, CoinflipPlugin.this.tailsData, ChatColor.GREEN + "Tails");
                        gui.setItem(4, result);
                        Player winningPlayer = winner.equals(bet.getSide()) ? better : challenger;
                        Player losingPlayer = winner.equals(bet.getSide()) ? challenger : better;
                        long winAmount = bet.getAmount() * 2L;
                        long taxAmount = 0;
                        if (!winningPlayer.hasPermission("coinflip.tax.bypass")) {
                            taxAmount = (long) (winAmount * CoinflipPlugin.this.taxRate);
                            winAmount -= taxAmount;
                        }
                        CoinflipPlugin.this.economy.withdrawPlayer((OfflinePlayer)losingPlayer, bet.getAmount());
                        CoinflipPlugin.this.economy.depositPlayer((OfflinePlayer)winningPlayer, winAmount);
                        if (taxAmount > 0) {
                            winningPlayer.sendMessage(ChatColor.GREEN + "You won $" + String.format("%,d", winAmount) + " in the coinflip! (Tax: $" + String.format("%,d", taxAmount) + ")");
                        } else {
                            winningPlayer.sendMessage(ChatColor.GREEN + "You won $" + String.format("%,d", winAmount) + " in the coinflip!");
                        }
                        losingPlayer.sendMessage(ChatColor.RED + "You lost $" + String.format("%,d", bet.getAmount()) + " in the coinflip!");
                        CoinflipPlugin.this.updatePlayerStats(winningPlayer.getUniqueId(), bet.getAmount(), true);
                        CoinflipPlugin.this.updatePlayerStats(losingPlayer.getUniqueId(), bet.getAmount(), false);
                        Bukkit.broadcastMessage(ChatColor.translateAlternateColorCodes('&', "&b"));
                        Bukkit.broadcastMessage(ChatColor.translateAlternateColorCodes('&', "&b&lCOINFLIP &8" + CoinflipPlugin.this.getPlayerRankAndName(winningPlayer) + " &7(" + winner + ") has defeated " + CoinflipPlugin.this.getPlayerRankAndName(losingPlayer) + " &7(" + (winner.equals("Heads") ? "Tails" : "Heads") + ") in a wager for an amount of &a$" + String.format("%,d", bet.getAmount() * 2) + "&7."));
                        challenger.playSound(challenger.getLocation(), CoinflipPlugin.this.winSound, CoinflipPlugin.this.winSoundVolume, CoinflipPlugin.this.winSoundPitch);
                        better.playSound(better.getLocation(), CoinflipPlugin.this.winSound, CoinflipPlugin.this.winSoundVolume, CoinflipPlugin.this.winSoundPitch);
                        CoinflipPlugin.this.coinflipHistory.add(new CoinflipPlugin.CoinflipHistory(winningPlayer.getName(), losingPlayer.getName(), winAmount, winner));
                        if (CoinflipPlugin.this.coinflipHistory.size() > 100)
                            CoinflipPlugin.this.coinflipHistory.remove(0);
                        cancel();
                        return;
                    }
                    if ((this.ticks - this.countdownTicks) % CoinflipPlugin.this.switchInterval == 0) {
                        ItemStack coin = ((this.ticks - this.countdownTicks) / CoinflipPlugin.this.switchInterval % 2 == 0) ? CoinflipPlugin.this.createItem(CoinflipPlugin.this.headsItem, CoinflipPlugin.this.headsData, ChatColor.AQUA + "Heads") : CoinflipPlugin.this.createItem(CoinflipPlugin.this.tailsItem, CoinflipPlugin.this.tailsData, ChatColor.GREEN + "Tails");
                        gui.setItem(4, coin);
                        challenger.playSound(challenger.getLocation(), CoinflipPlugin.this.switchSound, CoinflipPlugin.this.switchSoundVolume, CoinflipPlugin.this.switchSoundPitch);
                        better.playSound(better.getLocation(), CoinflipPlugin.this.switchSound, CoinflipPlugin.this.switchSoundVolume, CoinflipPlugin.this.switchSoundPitch);
                    }
                }
                this.ticks++;
            }
        }).runTaskTimer((Plugin)this, 0L, 1L);
    }

    private void createBet(Player player, long amount, String side) {
        this.economy.withdrawPlayer((OfflinePlayer)player, amount);
        this.activeBets.put(player.getUniqueId(), new CoinflipBet(player, amount, side));
        player.sendMessage(ChatColor.GREEN + "You've placed a " + formatAmount(amount) + " coinflip bet on " + side + ".");
    }

    private void updatePlayerStats(UUID playerId, long amount, boolean won) {
        PlayerStats stats = this.playerStats.computeIfAbsent(playerId, k -> new PlayerStats(playerId));
        if (won) {
            stats.addWin(amount);
        } else {
            stats.addLoss(amount);
        }
        savePlayerStats();
    }

    private ItemStack createItem(Material material, short data, String name, List<String> lore) {
        ItemStack item = new ItemStack(material, 1, data);
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName(name);
        if (lore != null)
            meta.setLore(lore);
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack createItem(Material material, short data, String name) {
        return createItem(material, data, name, (List<String>)null);
    }

    private ItemStack createPlayerHead(Player player, long amount, String side) {
        ItemStack head = new ItemStack(Material.SKULL_ITEM, 1, (short)3);
        SkullMeta meta = (SkullMeta)head.getItemMeta();
        meta.setOwner(player.getName());
        meta.setDisplayName(ChatColor.YELLOW + player.getName());
        PlayerStats stats = this.playerStats.getOrDefault(player.getUniqueId(), new PlayerStats(player.getUniqueId()));
        double ratio = (stats.getTotalGames() == 0) ? 0.0D : (stats.getWins() / stats.getTotalGames());
        List<String> lore = Arrays.asList(new String[] { ChatColor.GRAY + "Wager: " + ChatColor.GREEN +
                formatAmount(amount), ChatColor.GRAY + "Side: " + ChatColor.GOLD + side, "", ChatColor.AQUA + "" + ChatColor.BOLD + "WIN LOSS / RATIO", ChatColor.GRAY + "(" + ChatColor.GREEN + stats
                .getWins() + ChatColor.GRAY + "/" + ChatColor.RED + stats.getLosses() + ChatColor.GRAY + ", " + ChatColor.YELLOW + String.format("%.2f", new Object[] { Double.valueOf(ratio) }) + ")", "", ChatColor.AQUA + "" + ChatColor.BOLD + "WIN LOSS AMOUNT", "(" + ChatColor.GREEN +
                formatAmount(stats.getTotalWinAmount()) + ChatColor.GRAY + "/" + ChatColor.RED + formatAmount(stats.getTotalLossAmount()) + ")" });
        meta.setLore(lore);
        head.setItemMeta((ItemMeta)meta);
        return head;
    }

    private long parseAmount(String input) {
        input = input.toLowerCase().replaceAll("[^0-9a-zA-Z]", "");
        long multiplier = 1L;
        if (input.endsWith("k")) {
            multiplier = 1000L;
            input = input.substring(0, input.length() - 1);
        } else if (input.endsWith("m")) {
            multiplier = 1000000L;
            input = input.substring(0, input.length() - 1);
        } else if (input.endsWith("b")) {
            multiplier = 1000000000L;
            input = input.substring(0, input.length() - 1);
        } else if (input.endsWith("t")) {
            multiplier = 1000000000000L;
            input = input.substring(0, input.length() - 1);
        }
        return Long.parseLong(input) * multiplier;
    }

    private String formatAmount(long amount) {
        return "$" + String.format("%,d", amount);
    }

    private String getPlayerRankAndName(Player player) {
        String prefix = "";
        if (getServer().getPluginManager().getPlugin("Vault") != null) {
            RegisteredServiceProvider<Chat> rsp = getServer().getServicesManager().getRegistration(Chat.class);
            if (rsp != null) {
                Chat chat = (Chat)rsp.getProvider();
                prefix = chat.getPlayerPrefix(player);
            }
        }
        return ChatColor.translateAlternateColorCodes('&', prefix + player.getName());
    }

    private static class CoinflipBet {
        private final Player player;
        private final long amount;
        private final String side;

        public CoinflipBet(Player player, long amount, String side) {
            this.player = player;
            this.amount = amount;
            this.side = side;
        }

        public Player getPlayer() {
            return this.player;
        }

        public long getAmount() {
            return this.amount;
        }

        public String getSide() {
            return this.side;
        }
    }

    private static class PlayerStats {
        private final UUID playerId;
        private int wins;
        private int losses;
        private long totalWinAmount;
        private long totalLossAmount;

        public PlayerStats(UUID playerId) {
            this.playerId = playerId;
        }

        public UUID getPlayerId() {
            return this.playerId;
        }

        public int getWins() {
            return this.wins;
        }

        public int getLosses() {
            return this.losses;
        }

        public long getTotalWinAmount() {
            return this.totalWinAmount;
        }

        public long getTotalLossAmount() {
            return this.totalLossAmount;
        }

        public int getTotalGames() {
            return this.wins + this.losses;
        }

        public void addWin(long amount) {
            this.wins++;
            this.totalWinAmount += amount;
        }

        public void addLoss(long amount) {
            this.losses++;
            this.totalLossAmount += amount;
        }
    }
    private static class CoinflipHistory {
        private final String winner;
        private final String loser;
        private final long amount;
        private final String winnerSide;
        private final long timestamp;

        public CoinflipHistory(String winner, String loser, long amount, String winnerSide) {
            this.winner = winner;
            this.loser = loser;
            this.amount = amount;
            this.winnerSide = winnerSide;
            this.timestamp = System.currentTimeMillis();
        }

        public String getWinner() {
            return this.winner;
        }

        public String getLoser() {
            return this.loser;
        }

        public long getAmount() {
            return this.amount;
        }

        public String getWinnerSide() {
            return this.winnerSide;
        }

        public String getFormattedTime() {
            SimpleDateFormat sdf = new SimpleDateFormat("MM/dd HH:mm:ss");
            return sdf.format(new Date(this.timestamp));
        }
    }
}