package com.musicplayer.app.player;

import java.util.List;
import java.util.Random;

public class TrueRandomShuffler {
    
    public static <T> void shuffle(List<T> list) {
        if (list == null || list.size() <= 1) {
            return;
        }
        
        Random random = new Random(System.currentTimeMillis() + System.nanoTime());
        for (int i = list.size() - 1; i > 0; i--) {
            int j = random.nextInt(i + 1);
            swap(list, i, j);
        }
    }
    
    private static <T> void swap(List<T> list, int i, int j) {
        T temp = list.get(i);
        list.set(i, list.get(j));
        list.set(j, temp);
    }
}
