package com.occpl.platform.company.internal;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ImageTypesTest {

    private static byte[] bytes(int... v) {
        byte[] b = new byte[v.length];
        for (int i = 0; i < v.length; i++) {
            b[i] = (byte) v[i];
        }
        return b;
    }

    @Test
    void recognisesPngJpegAndWebpFromContent() {
        assertThat(ImageTypes.detect(bytes(0x89, 'P', 'N', 'G', 13, 10, 26, 10), false).get().extension()).isEqualTo("png");
        assertThat(ImageTypes.detect(bytes(0xFF, 0xD8, 0xFF, 0xE0), false).get().contentType()).isEqualTo("image/jpeg");
        assertThat(ImageTypes.detect(bytes('R', 'I', 'F', 'F', 0, 0, 0, 0, 'W', 'E', 'B', 'P'), false).get().extension())
                .isEqualTo("webp");
    }

    @Test
    void icoOnlyAllowedForFavicons() {
        byte[] ico = bytes(0, 0, 1, 0, 1, 0);
        assertThat(ImageTypes.detect(ico, true)).isPresent();
        assertThat(ImageTypes.detect(ico, false)).isEmpty();
    }

    @Test
    void rejectsSvgHtmlAndGarbageWhateverTheName() {
        assertThat(ImageTypes.detect("<svg xmlns=\"http://www.w3.org/2000/svg\"></svg>".getBytes(), true)).isEmpty();
        assertThat(ImageTypes.detect("<html><script>alert(1)</script>".getBytes(), true)).isEmpty();
        assertThat(ImageTypes.detect(bytes(1, 2), true)).isEmpty();
    }
}
