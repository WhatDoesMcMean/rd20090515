package me.kalmemarq.util;

import java.util.Random;

public class PerlinNoiseFilter {
    public int[] read(int width, int height, int levels) {
        Random random = new Random();
        int[] tmp = new int[width * height];
        int fuzz = 16;
        int step = width >> levels;

        int val;
        int ss;
        for(val = 0; val < height; val += step) {
            for(ss = 0; ss < width; ss += step) {
                tmp[ss + val * width] = (random.nextInt(256) - 128) * fuzz;
            }
        }

        for(step = width >> levels; step > 1; step /= 2) {
            val = 256 * (step << levels);
            ss = step / 2;

            int y;
            int x;
            int c;
            int r;
            int d;
            int mu;
            int ml;
            for(y = 0; y < height; y += step) {
                for(x = 0; x < width; x += step) {
                    c = tmp[(x) % width + (y) % height * width];
                    r = tmp[(x + step) % width + (y) % height * width];
                    d = tmp[(x) % width + (y + step) % height * width];
                    mu = tmp[(x + step) % width + (y + step) % height * width];
                    ml = (c + d + r + mu) / 4 + random.nextInt(val * 2) - val;
                    tmp[x + ss + (y + ss) * width] = ml;
                }
            }

            for(y = 0; y < height; y += step) {
                for(x = 0; x < width; x += step) {
                    c = tmp[x + y * width];
                    r = tmp[(x + step) % width + y * width];
                    d = tmp[x + (y + step) % width * width];
                    mu = tmp[(x + ss & width - 1) + (y + ss - step & height - 1) * width];
                    ml = tmp[(x + ss - step & width - 1) + (y + ss & height - 1) * width];
                    int m = tmp[(x + ss) % width + (y + ss) % height * width];
                    int u = (c + r + m + mu) / 4 + random.nextInt(val * 2) - val;
                    int l = (c + d + m + ml) / 4 + random.nextInt(val * 2) - val;
                    tmp[x + ss + y * width] = u;
                    tmp[x + (y + ss) * width] = l;
                }
            }
        }

        int[] result = new int[width * height];

        for(val = 0; val < height; ++val) {
            for(ss = 0; ss < width; ++ss) {
                result[ss + val * width] = tmp[ss % width + val % height * width] / 512 + 128;
            }
        }

        return result;
    }
}
