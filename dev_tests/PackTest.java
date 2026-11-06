import java.nio.file.*; import java.util.*; import java.util.regex.*;
public class PackTest {
  static String cls(java.util.function.BiPredicate<Integer,Integer> p){
    StringBuilder sb=new StringBuilder("["); int run=-1, prev=-1;
    for(int code=0xAC00; code<=0xD7A4; code++){
      boolean ok=false;
      if(code<=0xD7A3){int idx=code-0xAC00; ok=p.test((idx/28)%21, idx%28);}
      if(ok){ if(run<0) run=code; prev=code; }
      else if(run>=0){ flush(sb,run,prev); run=-1; }
    }
    return sb.append("]").toString();
  }
  static void flush(StringBuilder sb,int a,int b){ sb.append((char)a); if(b-a>=2) sb.append('-'); if(b>a) sb.append((char)b); }
  static String unesc(String s){ StringBuilder sb=new StringBuilder(); for(int i=0;i<s.length();i++){char c=s.charAt(i); if(c=='\\'&&i+1<s.length()){char d=s.charAt(i+1); if(d=='\\')sb.append('\\'); else if(d=='t')sb.append('\t'); else if(d=='n')sb.append('\n'); else if(d=='r')sb.append('\r'); else {sb.append(c).append(d);} i++;} else sb.append(c);} return sb.toString(); }
  static Map<String,String> tokens=new LinkedHashMap<>();
  static String expand(String s){ if(s.indexOf('{')<0) return s; for(var e:tokens.entrySet()) s=s.replace(e.getKey(), e.getValue()); return s; }
  record Rule(Pattern re, String repl, char mode, String src){}
  public static void main(String[] a) throws Exception {
    tokens.put("{받침}", cls((ju,jo)->jo!=0));
    tokens.put("{무받침}", cls((ju,jo)->jo==0));
    tokens.put("{ㄹ받침}", cls((ju,jo)->jo==8));
    tokens.put("{ㄴ받침}", cls((ju,jo)->jo==4));
    tokens.put("{ㅆ받침}", cls((ju,jo)->jo==20));
    tokens.put("{양성받침}", cls((ju,jo)->jo!=0&&(ju==0||ju==8)));
    List<Rule> rules=new ArrayList<>();
    for(String line: Files.readAllLines(Path.of(a[0]))){
      if(line.isBlank()||line.startsWith("#")) continue;
      String[] c=line.split("\t",-1);
      String f=unesc(c[0]), r=unesc(c[1]); String fl=c.length>2?c[2]:"";
      char m= fl.contains("H")?'H': fl.contains("C")?'C':'A';
      rules.add(new Rule(Pattern.compile(expand(f)), r, m, f));
    }
    int fail=0, total=0;
    for(String line: Files.readAllLines(Path.of(a[1]))){
      if(line.isBlank()||line.startsWith("#")) continue;
      String[] c=line.split("\t",-1); char mode=c[0].charAt(0); String in=c[1]; String exp=c.length>2?c[2]:in;
      String s=in;
      for(Rule r: rules){ if(r.mode!='A' && r.mode!=mode) continue; s=r.re.matcher(s).replaceAll(r.repl); }
      total++;
      if(!s.equals(exp)){ fail++; System.out.println("FAIL ["+mode+"] "+in+"\n   got: "+s+"\n   exp: "+exp); }
      // idempotency
      String s2=s; for(Rule r: rules){ if(r.mode!='A' && r.mode!=mode) continue; s2=r.re.matcher(s2).replaceAll(r.repl); }
      if(!s2.equals(s)) System.out.println("  (비멱등) ["+mode+"] "+in+" -> "+s+" -> "+s2);
    }
    System.out.println("total "+total+" fail "+fail);
  }
}
