package com.dinemaster.catalog.service;

import com.cloudinary.Cloudinary;
import com.cloudinary.utils.ObjectUtils;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Map;
import java.util.UUID;
import java.io.ByteArrayInputStream;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;

@Service
public class ImageStorageService {
    private static final long MAX_BYTES = 2L * 1024 * 1024;
    private static final int MAX_DIMENSION = 1200;
    private final Cloudinary cloudinary;
    public ImageStorageService(org.springframework.beans.factory.ObjectProvider<Cloudinary> provider) {
        cloudinary = provider.getIfAvailable();
    }

    public String store(MultipartFile file) throws IOException {
        if (cloudinary == null) throw new StorageUnavailableException();
        if (file == null || file.isEmpty()) throw new InvalidImageException("Product image is required");
        if (file.getSize() > MAX_BYTES) throw new InvalidImageException("Image must be 2 MB or smaller");
        byte[] original = file.getBytes();
        String inputFormat = detectFormat(original);
        if (inputFormat == null) throw new InvalidImageException("Only JPEG or PNG image content is accepted");
        BufferedImage source;
        try (ImageInputStream input = ImageIO.createImageInputStream(new ByteArrayInputStream(original))) {
            if (input == null) throw new InvalidImageException("Image content could not be read");
            var readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) throw new InvalidImageException("Only JPEG or PNG image content is accepted");
            ImageReader reader = readers.next();
            try {
                reader.setInput(input, true, true);
                int sourceWidth = reader.getWidth(0), sourceHeight = reader.getHeight(0);
                if (sourceWidth < 1 || sourceHeight < 1 || (long) sourceWidth * sourceHeight > 20_000_000L)
                    throw new InvalidImageException("Image dimensions are not supported");
                source = reader.read(0);
            } finally { reader.dispose(); }
        }
        if (source == null) throw new InvalidImageException("Image content could not be read");
        double ratio = Math.min(1d, (double) MAX_DIMENSION / Math.max(source.getWidth(), source.getHeight()));
        int width = Math.max(1, (int) Math.round(source.getWidth() * ratio));
        int height = Math.max(1, (int) Math.round(source.getHeight() * ratio));
        int imageType = "png".equals(inputFormat) ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB;
        BufferedImage resized = new BufferedImage(width, height, imageType);
        Graphics2D graphics = resized.createGraphics();
        graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        graphics.drawImage(source, 0, 0, width, height, null);
        graphics.dispose();
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ImageIO.write(resized, inputFormat, output); // Re-encoding removes source metadata.
        Map<?, ?> uploaded = cloudinary.uploader().upload(output.toByteArray(), ObjectUtils.asMap(
                "public_id", "dinemaster/menu/" + UUID.randomUUID(),
                "resource_type", "image", "type", "authenticated", "format", inputFormat));
        Object publicId = uploaded.get("public_id");
        if (publicId == null) throw new IOException("Image provider did not return an asset identifier");
        return cloudinary.url().secure(true).type("authenticated").signed(true).format(inputFormat)
                .generate(publicId.toString());
    }

    private String detectFormat(byte[] bytes) {
        if (bytes.length >= 8 && (bytes[0] & 0xff) == 0x89 && bytes[1] == 0x50 && bytes[2] == 0x4e && bytes[3] == 0x47)
            return "png";
        if (bytes.length >= 3 && (bytes[0] & 0xff) == 0xff && (bytes[1] & 0xff) == 0xd8 && (bytes[2] & 0xff) == 0xff)
            return "jpg";
        return null;
    }
    public static final class InvalidImageException extends RuntimeException {
        public InvalidImageException(String message) { super(message); }
    }
    public static final class StorageUnavailableException extends RuntimeException { }
}
