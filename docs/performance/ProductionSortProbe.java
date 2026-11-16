import com.radium.inkwell.core.source.SearchResult;
import com.radium.inkwell.ui.search.*;
import java.text.Collator;
import java.util.*;

// Actual production ordering, including Collator; comparator baseline uses today's precompiled parsers.
public class ProductionSortProbe {
  static long sink;
  static long key(SearchHit hit, SearchSort sort) {
    long value = sort == SearchSort.WORD_COUNT ? -1 : Long.MIN_VALUE;
    for (SearchResult r : hit.getResults()) value = Math.max(value, sort == SearchSort.WORD_COUNT
        ? SearchViewModelKt.parseWordCount(r.getWordCount()) : SearchViewModelKt.parseUpdateEpoch(r.getKind(), r.getIntro()));
    return value;
  }
  static List<SearchHit> run(List<SearchHit> hits, SearchSort sort, boolean cached) {
    if (cached) return SearchViewModelKt.orderSearchHits(hits, "", sort);
    Collator collator = Collator.getInstance(Locale.CHINA); collator.setStrength(Collator.PRIMARY);
    List<SearchHit> out = new ArrayList<>(hits);
    out.sort(Comparator.<SearchHit>comparingLong(h -> key(h, sort)).reversed()
        .thenComparing(h -> h.getResult().getTitle(), collator));
    return out;
  }
  static List<SearchHit> fresh(List<SearchHit> rows) {
    return rows.stream().map(h -> new SearchHit(h.getResults())).toList();
  }
  public static void main(String[] args) {
    List<SearchHit> rows = new ArrayList<>(); Random random = new Random(42);
    for(int i=0;i<2000;i++) {
      List<SearchResult> origins = new ArrayList<>();
      for(int j=0;j<3;j++) origins.add(new SearchResult("书"+i, "/"+i, "作者", null, null, null,
          (1+random.nextInt(500))+"."+random.nextInt(10)+"万字",
          "连载 · "+(2020+random.nextInt(7))+"-"+(1+random.nextInt(12))+"-"+(1+random.nextInt(28)),
          "s"+j, "来源", Collections.emptyMap()));
      rows.add(new SearchHit(List.copyOf(origins)));
    }
    for(SearchSort sort: List.of(SearchSort.WORD_COUNT, SearchSort.UPDATE_TIME)) {
      if(!run(rows,sort,false).equals(run(fresh(rows),sort,true))) throw new AssertionError("ordering differs");
      for(int w=0;w<6;w++){run(rows,sort,false);run(fresh(rows),sort,true);}
      for(String mode: List.of("comparator_baseline", "production_cold", "production_reused")) {
        double[] times=new double[9];
        for(int t=0;t<9;t++) {
          List<SearchHit> input=mode.equals("production_cold") ? fresh(rows) : rows;
          long start=System.nanoTime(); List<SearchHit> out=run(input,sort,!mode.equals("comparator_baseline"));
          times[t]=(System.nanoTime()-start)/1_000_000.0; sink+=out.size();
        }
        Arrays.sort(times);
        System.out.printf(Locale.ROOT,"n=2000 sources=3 sort=%s mode=%s median_ms=%.3f%n",sort,mode,times[4]);
      }
    }
  }
}
