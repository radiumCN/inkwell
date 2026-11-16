import com.radium.inkwell.ui.search.SearchViewModelKt;
import java.util.*;

// Calls the compiled application's actual parsers; replays only its key-extraction/sorting logic.
public class SortProbe {
  record Hit(int id, String[] words, String[] dates) {}
  static long sink, calls;
  static long key(Hit h, boolean dates) {
    long value = dates ? Long.MIN_VALUE : -1;
    String[] parts = dates ? h.dates : h.words;
    for (String raw : parts) {
      calls++;
      value = Math.max(value, dates ? SearchViewModelKt.parseUpdateEpoch(raw) : SearchViewModelKt.parseWordCount(raw));
    }
    return value;
  }
  static List<Hit> run(List<Hit> input, boolean dates, boolean precompute) {
    List<Hit> out = new ArrayList<>(input);
    if (precompute) {
      long[] keys = new long[input.size()];
      for (Hit h : input) keys[h.id] = key(h, dates);
      out.sort(Comparator.<Hit>comparingLong(h -> keys[h.id]).reversed().thenComparingInt(Hit::id));
    } else {
      out.sort(Comparator.<Hit>comparingLong(h -> key(h, dates)).reversed().thenComparingInt(Hit::id));
    }
    sink += out.get(0).id;
    return out;
  }
  public static void main(String[] args) {
    for (int size : new int[]{500, 2000}) {
      Random random = new Random(42);
      List<Hit> hits = new ArrayList<>();
      for (int i=0;i<size;i++) {
        String[] words = new String[3], dates = new String[3];
        for (int j=0;j<3;j++) {
          words[j] = (1+random.nextInt(500)) + "." + random.nextInt(10) + "万字";
          dates[j] = "连载 · " + (2020+random.nextInt(7)) + "-" + (1+random.nextInt(12)) + "-" + (1+random.nextInt(28));
        }
        hits.add(new Hit(i, words, dates));
      }
      for (boolean dates : new boolean[]{false,true}) {
        if (!run(hits,dates,false).equals(run(hits,dates,true))) throw new AssertionError("sort changed");
        for(int warm=0;warm<6;warm++){run(hits,dates,false);run(hits,dates,true);}
        for(boolean precompute:new boolean[]{false,true}) {
          double[] ms = new double[9]; long parseCalls=0;
          for(int trial=0;trial<ms.length;trial++) {
            calls=0; long start=System.nanoTime();run(hits,dates,precompute);
            ms[trial]=(System.nanoTime()-start)/1_000_000.0;parseCalls=calls;
          }
          Arrays.sort(ms);
          System.out.printf(Locale.ROOT,"n=%d sources=3 sort=%s mode=%s median_ms=%.3f parser_calls=%d%n",size,dates?"date":"words",precompute?"precomputed":"current",ms[4],parseCalls);
        }
      }
    }
  }
}
