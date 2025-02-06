package com.example.timevip;

import net.luckperms.api.LuckPerms;
import net.luckperms.api.model.group.Group;
import net.luckperms.api.model.user.User;
import net.luckperms.api.node.types.InheritanceNode;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;

import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class TimeVIP extends JavaPlugin implements TabCompleter {
    private LuckPerms luckPerms;
    private final Pattern timePattern = Pattern.compile("(\\d+)([dhms])");
    private static final String DEFAULT_GROUP = "default";

    @Override
    public void onEnable() {
        setupLuckPerms();
        getCommand("timevip").setExecutor(this);
        getCommand("timevip").setTabCompleter(this);
    }

    private void setupLuckPerms() {
        RegisteredServiceProvider<LuckPerms> provider = Bukkit.getServicesManager().getRegistration(LuckPerms.class);
        if (provider != null) {
            luckPerms = provider.getProvider();
            getLogger().info("LuckPerms API succesfully loaded!");
        } else {
            getLogger().severe("LuckPerms not found! Plugin is being disabled...");
            getServer().getPluginManager().disablePlugin(this);
        }
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!command.getName().equalsIgnoreCase("timevip")) {
            return false;
        }

        if (args.length == 0) {
            sender.sendMessage("§Usage: /timevip give <player> <group> <time>");
            return true;
        }

        if (args[0].equalsIgnoreCase("reload")) {
            if (!sender.hasPermission("timevip.reload")) {
                sender.sendMessage("§cYou are not permission to use this command!");
                return true;
            }
            reloadConfig();
            setupLuckPerms();
            sender.sendMessage("§aTimeVIP reloaded!");
            return true;
        }

        if (!args[0].equalsIgnoreCase("give") || args.length != 4) {
            sender.sendMessage("§Usage: /timevip give <player> <group> <time>");
            return true;
        }

        if (!sender.hasPermission("timevip.give")) {
            sender.sendMessage("§cYou are not permission to use this command!");
            return true;
        }

        Player target = Bukkit.getPlayer(args[1]);
        if (target == null) {
            sender.sendMessage("§cPlayer not online!");
            return true;
        }

        String group = args[2];
        if (luckPerms.getGroupManager().getGroup(group) == null) {
            sender.sendMessage("§cGroup not found!");
            return true;
        }

        String timeStr = args[3];
        long duration;
        try {
            duration = parseDuration(timeStr);
            if (duration <= 0) {
                sender.sendMessage("§cInvalid time! Duration must be greater than 0.");
                return true;
            }
        } catch (IllegalArgumentException e) {
            sender.sendMessage("§cInvalid duration format! Example: 30d 12h 45m 30s or 30d12h45m30s");
            return true;
        }

        User user = luckPerms.getUserManager().getUser(target.getUniqueId());
        if (user == null) {
            sender.sendMessage("§cFailed to load user data!");
            return true;
        }

        user.data().clear(node -> node.getKey().startsWith("group."));

        InheritanceNode vipNode = InheritanceNode.builder(group)
                .expiry(Duration.of(duration, ChronoUnit.SECONDS))
                .build();

        user.data().add(vipNode);

        user.setPrimaryGroup(group);

        Bukkit.getScheduler().runTaskLater(this, () -> {
            User expiredUser = luckPerms.getUserManager().getUser(target.getUniqueId());
            if (expiredUser != null) {
                expiredUser.data().clear(node -> node.getKey().startsWith("group."));
                InheritanceNode defaultNode = InheritanceNode.builder(DEFAULT_GROUP).build();
                expiredUser.data().add(defaultNode);
                expiredUser.setPrimaryGroup(DEFAULT_GROUP);
                luckPerms.getUserManager().saveUser(expiredUser);
                luckPerms.getMessagingService().ifPresent(service -> service.pushUserUpdate(expiredUser));
            }
        }, duration * 20L); 

        luckPerms.getUserManager().saveUser(user);

        luckPerms.getMessagingService().ifPresent(service -> service.pushUserUpdate(user));

        sender.sendMessage("§a Player named " + target.getName() + " has been given group " + group + " for " + timeStr + "!");
        target.sendMessage("§a" + timeStr + " you have been added to the " + group + " group for a period of time!");

        return true;
    }

    private long parseDuration(String time) {
        long totalSeconds = 0;
        Matcher matcher = timePattern.matcher(time);
        boolean found = false;

        while (matcher.find()) {
            found = true;
            long value = Long.parseLong(matcher.group(1));
            String unit = matcher.group(2);

            switch (unit.toLowerCase()) {
                case "d":
                    totalSeconds += TimeUnit.DAYS.toSeconds(value);
                    break;
                case "h":
                    totalSeconds += TimeUnit.HOURS.toSeconds(value);
                    break;
                case "m":
                    totalSeconds += TimeUnit.MINUTES.toSeconds(value);
                    break;
                case "s":
                    totalSeconds += value;
                    break;
            }
        }

        if (!found) {
            throw new IllegalArgumentException("Invalid time format");
        }

        return totalSeconds;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!command.getName().equalsIgnoreCase("timevip")) {
            return null;
        }

        List<String> completions = new ArrayList<>();

        if (args.length == 1) {
            if (sender.hasPermission("timevip.give")) completions.add("give");
            if (sender.hasPermission("timevip.reload")) completions.add("reload");
        } else if (args.length == 2 && args[0].equalsIgnoreCase("give") && sender.hasPermission("timevip.give")) {
            completions.addAll(Bukkit.getOnlinePlayers().stream()
                    .map(Player::getName)
                    .collect(Collectors.toList()));
        } else if (args.length == 3 && args[0].equalsIgnoreCase("give") && sender.hasPermission("timevip.give")) {
            completions.addAll(luckPerms.getGroupManager().getLoadedGroups().stream()
                    .map(Group::getName)
                    .filter(name -> !name.equalsIgnoreCase(DEFAULT_GROUP))
                    .collect(Collectors.toList()));
        } else if (args.length == 4 && args[0].equalsIgnoreCase("give") && sender.hasPermission("timevip.give")) {
            String input = args[3].toLowerCase();
            List<String> suggestions = new ArrayList<>();
            
            suggestions.addAll(Arrays.asList(
                "1d", "2d", "3d", "7d", "14d", "30d",
                "1h", "2h", "6h", "12h", "24h",
                "15m", "30m", "45m", "60m",
                "30s", "60s", "120s"
            ));
            
            completions.addAll(suggestions.stream()
                    .filter(s -> s.startsWith(input))
                    .collect(Collectors.toList()));
        }

        return completions.stream()
                .filter(completion -> completion.toLowerCase().startsWith(args[args.length - 1].toLowerCase()))
                .collect(Collectors.toList());
    }
} 
