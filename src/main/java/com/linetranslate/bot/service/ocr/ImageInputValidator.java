package com.linetranslate.bot.service.ocr;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Iterator;
import java.util.Locale;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/** Validates decoded facts before allocating the full image and normalizes EXIF rotation. */
@Component
public class ImageInputValidator {

    private final ImageTranslationProperties properties;
    private final boolean strict;

    @Autowired
    public ImageInputValidator(ImageTranslationProperties properties) {
        this(properties, true);
    }

    private ImageInputValidator(ImageTranslationProperties properties, boolean strict) {
        this.properties = properties;
        this.strict = strict;
    }

    static ImageInputValidator trustedTestSeam(ImageTranslationProperties properties) {
        return new ImageInputValidator(properties, false);
    }

    public ValidatedImage validate(byte[] bytes, String claimedContentType) {
        if (bytes == null || bytes.length == 0) {
            throw new InvalidImageException("Image is empty");
        }
        if (bytes.length > properties.maxFileSizeBytes()) {
            throw new InvalidImageException("Image exceeds the maximum file size");
        }
        if (!strict) {
            String contentType = claimedContentType == null || claimedContentType.isBlank()
                    ? "image/jpeg"
                    : claimedContentType;
            return new ValidatedImage(
                    bytes, contentType, new BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB));
        }

        try (ImageInputStream input = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
            if (input == null) {
                throw new InvalidImageException("Image format is unreadable");
            }
            Iterator<ImageReader> readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) {
                throw new InvalidImageException("Image format is unsupported");
            }
            ImageReader reader = readers.next();
            try {
                reader.setInput(input, true, true);
                String contentType = canonicalContentType(reader.getFormatName());
                validateClaimedType(claimedContentType, contentType);
                int width = reader.getWidth(0);
                int height = reader.getHeight(0);
                validateDimensions(width, height);
                BufferedImage decoded = reader.read(0);
                int orientation = "image/jpeg".equals(contentType) ? readExifOrientation(bytes) : 1;
                BufferedImage normalized = applyOrientation(decoded, orientation);
                if (orientation != 1) {
                    return new ValidatedImage(encodePng(normalized), "image/png", normalized);
                }
                return new ValidatedImage(bytes, contentType, decoded);
            } finally {
                reader.dispose();
            }
        } catch (InvalidImageException failure) {
            throw failure;
        } catch (IOException | RuntimeException failure) {
            throw new InvalidImageException("Image could not be decoded", failure);
        }
    }

    private void validateDimensions(int width, int height) {
        long pixels = (long) width * height;
        if (width <= 0 || height <= 0
                || width > properties.maxDimension()
                || height > properties.maxDimension()
                || pixels > properties.maxPixels()) {
            throw new InvalidImageException("Image dimensions exceed the configured limit");
        }
    }

    private static String canonicalContentType(String formatName) {
        String format = formatName == null ? "" : formatName.toLowerCase(Locale.ROOT);
        return switch (format) {
            case "jpeg", "jpg" -> "image/jpeg";
            case "png" -> "image/png";
            default -> throw new InvalidImageException("Only JPEG and PNG images are supported");
        };
    }

    private static void validateClaimedType(String claimed, String decoded) {
        String normalized = claimed == null ? "" : claimed.split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
        if ("image/jpg".equals(normalized)) {
            normalized = "image/jpeg";
        }
        if (!normalized.equals(decoded)) {
            throw new InvalidImageException("Claimed image type does not match decoded type");
        }
    }

    private static byte[] encodePng(BufferedImage image) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        if (!ImageIO.write(image, "png", output)) {
            throw new IOException("PNG writer is unavailable");
        }
        return output.toByteArray();
    }

    private static int readExifOrientation(byte[] jpeg) {
        if (jpeg.length < 4 || unsigned(jpeg[0]) != 0xFF || unsigned(jpeg[1]) != 0xD8) {
            return 1;
        }
        int offset = 2;
        while (offset + 4 <= jpeg.length && unsigned(jpeg[offset]) == 0xFF) {
            int marker = unsigned(jpeg[offset + 1]);
            if (marker == 0xDA || marker == 0xD9) {
                break;
            }
            int length = (unsigned(jpeg[offset + 2]) << 8) | unsigned(jpeg[offset + 3]);
            if (length < 2 || offset + 2 + length > jpeg.length) {
                return 1;
            }
            if (marker == 0xE1 && length >= 10 && hasExifHeader(jpeg, offset + 4)) {
                return orientationFromTiff(jpeg, offset + 10, length - 8);
            }
            offset += length + 2;
        }
        return 1;
    }

    private static boolean hasExifHeader(byte[] bytes, int offset) {
        byte[] header = {'E', 'x', 'i', 'f', 0, 0};
        if (offset + header.length > bytes.length) {
            return false;
        }
        for (int i = 0; i < header.length; i++) {
            if (bytes[offset + i] != header[i]) {
                return false;
            }
        }
        return true;
    }

    private static int orientationFromTiff(byte[] bytes, int tiffOffset, int available) {
        try {
            if (available < 14 || tiffOffset + available > bytes.length) {
                return 1;
            }
            ByteOrder order;
            if (bytes[tiffOffset] == 'I' && bytes[tiffOffset + 1] == 'I') {
                order = ByteOrder.LITTLE_ENDIAN;
            } else if (bytes[tiffOffset] == 'M' && bytes[tiffOffset + 1] == 'M') {
                order = ByteOrder.BIG_ENDIAN;
            } else {
                return 1;
            }
            ByteBuffer buffer = ByteBuffer.wrap(bytes, tiffOffset, available).slice().order(order);
            if (Short.toUnsignedInt(buffer.getShort(2)) != 42) {
                return 1;
            }
            int ifdOffset = buffer.getInt(4);
            if (ifdOffset < 8 || ifdOffset + 2 > buffer.limit()) {
                return 1;
            }
            int entries = Short.toUnsignedInt(buffer.getShort(ifdOffset));
            for (int index = 0; index < entries; index++) {
                int entry = ifdOffset + 2 + index * 12;
                if (entry + 12 > buffer.limit()) {
                    return 1;
                }
                if (Short.toUnsignedInt(buffer.getShort(entry)) == 0x0112) {
                    int orientation = Short.toUnsignedInt(buffer.getShort(entry + 8));
                    return orientation >= 1 && orientation <= 8 ? orientation : 1;
                }
            }
        } catch (IndexOutOfBoundsException ignored) {
            return 1;
        }
        return 1;
    }

    private static BufferedImage applyOrientation(BufferedImage source, int orientation) {
        if (orientation == 1) {
            return source;
        }
        int width = source.getWidth();
        int height = source.getHeight();
        boolean swapsAxes = orientation >= 5;
        int type = source.getColorModel().hasAlpha() ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB;
        BufferedImage result = new BufferedImage(swapsAxes ? height : width, swapsAxes ? width : height, type);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int targetX;
                int targetY;
                switch (orientation) {
                    case 2 -> { targetX = width - 1 - x; targetY = y; }
                    case 3 -> { targetX = width - 1 - x; targetY = height - 1 - y; }
                    case 4 -> { targetX = x; targetY = height - 1 - y; }
                    case 5 -> { targetX = y; targetY = x; }
                    case 6 -> { targetX = height - 1 - y; targetY = x; }
                    case 7 -> { targetX = height - 1 - y; targetY = width - 1 - x; }
                    case 8 -> { targetX = y; targetY = width - 1 - x; }
                    default -> { targetX = x; targetY = y; }
                }
                result.setRGB(targetX, targetY, source.getRGB(x, y));
            }
        }
        return result;
    }

    private static int unsigned(byte value) {
        return Byte.toUnsignedInt(value);
    }
}
