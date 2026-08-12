package com.linetranslate.bot.service.imageproxy;

import java.net.URI;

/** Clean public URLs for one translated image and its LINE preview. */
public record ImageProxyLinks(URI original, URI preview) {

    public ImageProxyLinks {
        if (original == null || preview == null) {
            throw new IllegalArgumentException("Image proxy links are required");
        }
    }
}
