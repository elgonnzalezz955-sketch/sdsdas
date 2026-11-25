package com.donutmarket;

import com.donutmarket.ui.MarketScreen;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.text.Text;
import org.lwjgl.glfw.GLFW;

public class DonutMarketClient implements ClientModInitializer {
    public static KeyBinding OPEN_KEY;

    @Override
    public void onInitializeClient() {
        MarketConfig.get();
        PriceStore.get().load();

        OPEN_KEY = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.donutmarket.open", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_M, "category.donutmarket"));

        ClientTickEvents.END_CLIENT_TICK.register(mc -> {
            while (OPEN_KEY.wasPressed()) {
                if (mc.currentScreen == null) mc.setScreen(new MarketScreen());
            }
        });

        ClientLifecycleEvents.CLIENT_STARTED.register(mc -> MarketTracker.get().start());
        ClientLifecycleEvents.CLIENT_STOPPING.register(mc -> MarketTracker.get().stop());

        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) ->
                dispatcher.register(ClientCommandManager.literal("dmarket")
                        .executes(c -> { openScreen(); return 1; })
                        .then(ClientCommandManager.literal("open").executes(c -> { openScreen(); return 1; }))
                        .then(ClientCommandManager.literal("status").executes(c -> {
                            c.getSource().sendFeedback(Text.literal("[DonutMarket] " + MarketTracker.get().statusLine()));
                            return 1;
                        }))
                        .then(ClientCommandManager.literal("key")
                                .then(ClientCommandManager.argument("token", StringArgumentType.greedyString())
                                        .executes(c -> {
                                            MarketConfig.get().setApiKey(StringArgumentType.getString(c, "token").trim());
                                            c.getSource().sendFeedback(Text.literal("[DonutMarket] API key saved."));
                                            return 1;
                                        })))));
    }

    private static void openScreen() {
        MinecraftClient mc = MinecraftClient.getInstance();
        mc.execute(() -> mc.setScreen(new MarketScreen()));
    }
}
