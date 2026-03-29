package dev.nathsys.immersivetp;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.util.*;

public final class ImmersiveTP extends JavaPlugin {

    Map<UUID, Set<UUID>> requests = new HashMap<>();
    private final Map<UUID, Location> homes = new HashMap<>();
    private File homeFile;
    private FileConfiguration homeConfig;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        Objects.requireNonNull(getCommand("itp")).setExecutor(this::onITP);
        Objects.requireNonNull(getCommand("itpaccept")).setExecutor(this::onAccept);
        Objects.requireNonNull(getCommand("itpsethome")).setExecutor(this::onSetHome);
        Objects.requireNonNull(getCommand("itphome")).setExecutor(this::onHome);

        loadHomes();
    }

    private void loadHomes() {
        homeFile = new File(getDataFolder(), "homes.yml");
        if (!homeFile.exists()) {
            try {
                homeFile.createNewFile();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
        homeConfig = YamlConfiguration.loadConfiguration(homeFile);

        for (String key : homeConfig.getKeys(false)) {
            UUID playerUUID = UUID.fromString(key);
            double x = homeConfig.getDouble(key + ".x");
            double y = homeConfig.getDouble(key + ".y");
            double z = homeConfig.getDouble(key + ".z");
            float yaw = (float) homeConfig.getDouble(key + ".yaw");
            float pitch = (float) homeConfig.getDouble(key + ".pitch");
            String world = homeConfig.getString(key + ".world");
            Location loc = new Location(Bukkit.getWorld(world), x, y, z, yaw, pitch);
            homes.put(playerUUID, loc);
        }
    }

    private void saveHomes() {
        for (Map.Entry<UUID, Location> entry : homes.entrySet()) {
            String key = entry.getKey().toString();
            Location loc = entry.getValue();
            homeConfig.set(key + ".x", loc.getX());
            homeConfig.set(key + ".y", loc.getY());
            homeConfig.set(key + ".z", loc.getZ());
            homeConfig.set(key + ".yaw", loc.getYaw());
            homeConfig.set(key + ".pitch", loc.getPitch());
            homeConfig.set(key + ".world", loc.getWorld().getName());
        }
        try {
            homeConfig.save(homeFile);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private boolean charge(Player player) {
        FileConfiguration cfg = getConfig();
        if (!cfg.getBoolean("cost.enabled")) return true;

        Material mat = Material.valueOf(cfg.getString("cost.item"));
        String nicemat = mat.name().toLowerCase().replace("_", " ");

        int amount = cfg.getInt("cost.amount");

        int count = 0;
        for (ItemStack item : player.getInventory().getContents()) {
            if (item != null && item.getType() == mat) {
                count += item.getAmount();
            }
        }

        if (count < amount) {
            player.sendMessage(Component.text("You need " + amount + " " + nicemat + " to teleport.", NamedTextColor.RED));
            return false;
        }

        int remaining = amount;
        for (ItemStack item : player.getInventory().getContents()) {
            if (item == null || item.getType() != mat) continue;

            int take = Math.min(item.getAmount(), remaining);
            item.setAmount(item.getAmount() - take);
            remaining -= take;

            if (remaining <= 0) break;
        }

        return true;
    }

    private boolean onHome(CommandSender sender, Command cmd, String label, String[] args) {
        if (!(sender instanceof Player player)) return true;

        Location loc = homes.get(player.getUniqueId());
        if (loc == null) {
            player.sendMessage(Component.text("No home set.", NamedTextColor.RED));
            return true;
        }

        if (!charge(player)) return true;

        player.teleport(loc);
        player.sendMessage(Component.text("Teleported home!", NamedTextColor.GREEN));
        return true;
    }

    private boolean onSetHome(CommandSender sender, Command cmd, String label, String[] args) {
        if (!(sender instanceof Player player)) return true;

        homes.put(player.getUniqueId(), player.getLocation());
        saveHomes();

        player.sendMessage(Component.text("Home set!", NamedTextColor.GREEN));
        return true;
    }

    private boolean onAccept(CommandSender sender, Command cmd, String label, String[] args) {
        if (!(sender instanceof Player target)) return true;

        if (args.length != 1) {
            target.sendMessage(Component.text("Invalid request.", NamedTextColor.RED));
            return true;
        }

        UUID requesterId;
        try {
            requesterId = UUID.fromString(args[0]);
        } catch (IllegalArgumentException e) {
            target.sendMessage(Component.text("Invalid request ID.", NamedTextColor.RED));
            return true;
        }

        Player requester = Bukkit.getPlayer(requesterId);
        if (requester == null) {
            target.sendMessage(Component.text("Requester offline.", NamedTextColor.RED));
            return true;
        }

        Set<UUID> reqs = requests.get(target.getUniqueId());

        if (reqs == null || !reqs.contains(requesterId)) {
            target.sendMessage(Component.text("No pending request from that player.", NamedTextColor.RED));
            return true;
        }

        if (!charge(requester)) {
            target.sendMessage(Component.text(requester.getName() + " does not have enough items to teleport.", NamedTextColor.RED));
            requester.sendMessage(Component.text("You don't have enough items to teleport.", NamedTextColor.RED));
            return true;
        }

        requester.teleport(target.getLocation());

        requester.sendMessage(Component.text("Teleported!", NamedTextColor.GREEN));
        target.sendMessage(Component.text(requester.getName() + " teleported to you.", NamedTextColor.GREEN));

        reqs.remove(requesterId);
        if (reqs.isEmpty()) {
            requests.remove(target.getUniqueId());
        }

        return true;
    }

    private boolean onITP(CommandSender sender, Command cmd, String label, String[] args) {
        if (!(sender instanceof Player player)) return true;

        if (args.length != 1) {
            player.sendMessage(Component.text("Usage: /itp <player>", NamedTextColor.RED));
            return true;
        }

        Player target = Bukkit.getPlayer(args[0]);
        if (target == null) {
            player.sendMessage(Component.text("Player not found.", NamedTextColor.RED));
            return true;
        }

        if (target.getUniqueId().equals(player.getUniqueId())) {
            player.sendMessage(Component.text("Can't teleport to yourself.", NamedTextColor.RED));
            return true;
        }

        Set<UUID> set = requests.computeIfAbsent(target.getUniqueId(), k -> new HashSet<>());

        if (set.contains(player.getUniqueId())) {
            player.sendMessage(Component.text("You already sent a request.", NamedTextColor.RED));
            return true;
        }

        set.add(player.getUniqueId());

        player.sendMessage(Component.text("Teleport request sent to " + target.getName(), NamedTextColor.YELLOW));

        Component message = Component.text()
                .append(Component.text(player.getName(), NamedTextColor.GREEN).decorate(TextDecoration.BOLD))
                .append(Component.text(" wants to teleport to you ", NamedTextColor.WHITE))
                .append(Component.text("[CLICK TO ACCEPT]", NamedTextColor.AQUA)
                        .decorate(TextDecoration.BOLD)
                        .clickEvent(ClickEvent.runCommand("/itpaccept " + player.getUniqueId()))
                        .hoverEvent(HoverEvent.showText(
                                Component.text("Click to accept teleport request", NamedTextColor.YELLOW)
                        )))
                .build();

        target.sendMessage(message);

        return true;
    }

    @Override
    public void onDisable() {
        saveHomes();
        getLogger().info("Plugin disabled!");
    }
}
