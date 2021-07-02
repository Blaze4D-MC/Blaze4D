package me.hydos.blaze4d.mixin.texture;

import com.mojang.blaze3d.systems.RenderSystem;
import me.hydos.blaze4d.api.GlobalRenderSystem;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.texture.AbstractTexture;
import net.minecraft.client.texture.TextureManager;
import net.minecraft.util.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(RenderSystem.class)
public class RenderSystemMixin {

    @Inject(method = "setShaderTexture(ILnet/minecraft/util/Identifier;)V", remap = false, at = @At("HEAD"), cancellable = true)
    private static void setTexture(int i, Identifier identifier, CallbackInfo ci) { // TODO: maybe don't ignore layer id?
        TextureManager textureManager = MinecraftClient.getInstance().getTextureManager();
        AbstractTexture abstractTexture = textureManager.getTexture(identifier);
        GlobalRenderSystem.boundTextureIds[GlobalRenderSystem.activeTexture] = abstractTexture.getGlId();
        ci.cancel();
    }
}
