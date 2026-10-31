package id.kohryan.remotecodex;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.text.InputType;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.Base64;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.widget.*;
import org.json.*;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.util.*;
import java.util.concurrent.*;

public class MainActivity extends Activity {
    static final int BG = Color.rgb(12,17,24), CARD = Color.rgb(23,31,42), INK = Color.rgb(238,244,248), MUTED = Color.rgb(153,171,188), MINT = Color.rgb(110,231,199);
    final Handler handler = new Handler(Looper.getMainLooper());
    final ExecutorService io = Executors.newFixedThreadPool(3);
    final LinkedHashMap<String, JSONObject> transcript = new LinkedHashMap<>();
    LinearLayout root, list, messages, approvalBox;
    ScrollView scroll;
    TextView connection, activity;
    EditText composer;
    Button send, stop, older;
    String base = "", token = "", screen = "", threadId = "", title = "", activeTurn = "", nextThreads = "", nextTurns = "", fingerprint = "", approvalFingerprint = "", retryText = "", retryId = "", search = "";
    JSONObject status = new JSONObject();
    JSONArray threads = new JSONArray();
    boolean foreground = false, polling = false, sending = false, pagedThreads = false, pagedTurns = false;
    int generation = 0;

    interface Work { JSONObject run() throws Exception; }
    interface Success { void accept(JSONObject value) throws Exception; }
    final Runnable poll = new Runnable() {
        @Override public void run() {
            if (!foreground) return;
            if (!polling && !sending) {
                if (screen.equals("chat")) loadChat(false);
                else if (screen.equals("list")) loadThreads(false);
            }
            handler.postDelayed(this, screen.equals("chat") ? 1200 : 5000);
        }
    };
    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        SharedPreferences prefs = getSharedPreferences("connection", MODE_PRIVATE);
        base = prefs.getString("url", "");
        try { token = decrypt(prefs.getString("token", "")); } catch (Exception ignored) { token = ""; }
        if (base.isEmpty() || token.isEmpty()) setup();
        else connect(false);
    }
    @Override public void onResume() { super.onResume(); foreground = true; handler.removeCallbacks(poll); handler.post(poll); }
    @Override public void onPause() { foreground = false; handler.removeCallbacks(poll); super.onPause(); }
    @Override public void onDestroy() { handler.removeCallbacksAndMessages(null); io.shutdownNow(); super.onDestroy(); }
    @Override public void onBackPressed() { if (screen.equals("chat")) showThreads(); else if (screen.equals("setup") && !token.isEmpty()) connect(false); else super.onBackPressed(); }

    int dp(float value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    GradientDrawable background(int color, int radius) { GradientDrawable d = new GradientDrawable(); d.setColor(color); d.setCornerRadius(dp(radius)); return d; }
    LinearLayout column() { LinearLayout v = new LinearLayout(this); v.setOrientation(LinearLayout.VERTICAL); return v; }
    LinearLayout row() { LinearLayout v = new LinearLayout(this); v.setGravity(Gravity.CENTER_VERTICAL); return v; }
    TextView label(String text, int size, int color) { TextView v = new TextView(this); v.setText(text); v.setTextSize(size); v.setTextColor(color); v.setPadding(0,dp(5),0,dp(5)); return v; }
    Button button(String text, Runnable action) {
        Button b = new Button(this); b.setText(text); b.setTextSize(13); b.setAllCaps(false); b.setTextColor(MINT); b.setBackground(background(CARD,12));
        b.setPadding(dp(14),dp(4),dp(14),dp(4)); b.setMinHeight(dp(46)); b.setMinimumHeight(dp(46)); b.setOnClickListener(v -> action.run()); return b;
    }
    EditText field(String hint, boolean secret) {
        EditText e = new EditText(this); e.setTextSize(15); e.setTextColor(INK); e.setHintTextColor(MUTED); e.setHint(hint); e.setPadding(dp(16),dp(14),dp(16),dp(14)); e.setBackground(background(CARD,12));
        e.setInputType(secret ? InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD : InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        return e;
    }
    void gap(LinearLayout parent, int height) { View v = new View(this); parent.addView(v,new LinearLayout.LayoutParams(1,dp(height))); }
    void frame(String page) {
        screen = page; generation++; fingerprint = ""; approvalFingerprint = "";
        root = column(); root.setPadding(dp(20),dp(12),dp(20),dp(12)); root.setBackgroundColor(BG);
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.ime());
                v.setPadding(dp(20)+bars.left,dp(12)+bars.top,dp(20)+bars.right,dp(12)+bars.bottom);
            } else v.setPadding(dp(20),dp(12)+insets.getSystemWindowInsetTop(),dp(20),dp(12)+insets.getSystemWindowInsetBottom());
            return insets;
        });
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
        setContentView(root); root.requestApplyInsets();
    }
    void heading(String main, String sub) {
        TextView brand = label("⌁  REMOTE CODEX",12,MINT); brand.setLetterSpacing(.18f); root.addView(brand); gap(root,18);
        TextView h = label(main,30,INK); h.setTypeface(null,Typeface.BOLD); root.addView(h); root.addView(label(sub,14,MUTED)); gap(root,20);
    }
    void error(String message) {
        if (isFinishing() || isDestroyed()) return;
        new AlertDialog.Builder(this).setTitle("Periksa koneksi").setMessage(message).setPositiveButton("OK",null).show();
    }
    void task(Work work, Success done, boolean visibleError) {
        int view = generation;
        io.submit(() -> {
            try { JSONObject result = work.run(); handler.post(() -> {
                if (isDestroyed() || view != generation) return;
                try { done.accept(result); } catch (Exception e) { if (visibleError) error(e.getMessage()); }
            }); } catch (Exception e) { handler.post(() -> {
                if (isDestroyed() || view != generation) return;
                if (visibleError) error(e.getMessage());
                else if (connection != null) connection.setText("● Terputus · " + e.getMessage());
            }); }
        });
    }
    void setup() {
        frame("setup"); heading("Codex, dari HP.","Komputermu yang bekerja. Kamu yang mengarahkan.");
        root.addView(label("01  ALAMAT KOMPUTER",12,MUTED));
        EditText url = field("http://192.168.1.10:8787",false); url.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI); url.setSingleLine(true); url.setText(base); root.addView(url); gap(root,18);
        root.addView(label("02  TOKEN PAIRING",12,MUTED)); EditText secret = field("Tempel token dari komputer",true); secret.setSingleLine(true); secret.setText(token); root.addView(secret); gap(root,24);
        Button connect = button("Hubungkan ke Codex  →", () -> {
            String newBase = url.getText().toString().trim().replaceAll("/+$", ""), newToken = secret.getText().toString().trim();
            if (newToken.length() < 32) { error("Token minimal 32 karakter. Buka file .private/token di komputer."); return; }
            task(() -> { validateAddress(newBase); return request(newBase,newToken,"GET","/api/status",null); }, result -> {
                String encrypted = encrypt(newToken);
                getSharedPreferences("connection",MODE_PRIVATE).edit().putString("url",newBase).putString("token",encrypted).apply();
                base = newBase; token = newToken; status = result; threads=new JSONArray(); nextThreads=""; pagedThreads=false; showThreads();
            },true);
        });
        root.addView(connect,new LinearLayout.LayoutParams(-1,dp(52))); gap(root,24);
        root.addView(label("Gunakan Wi-Fi yang sama atau VPN Tailscale. Komputer dan bridge harus tetap menyala.",14,MUTED));
        root.addView(label("Token tersimpan terenkripsi di Android Keystore. Koneksi HTTP hanya diizinkan ke alamat jaringan privat.",12,MUTED));
    }
    void connect(boolean ignored) {
        frame("connecting"); heading("Menghubungkan…",base);
        root.addView(button("Pengaturan koneksi",this::setup));
        task(() -> api("GET","/api/status",null), result -> { status = result; showThreads(); }, true);
    }
    void showThreads() {
        frame("list"); heading("Ruang kerja.","Lanjutkan ide dari mana saja.");
        LinearLayout actions = row(); actions.addView(button("+ Chat baru",this::newChat),new LinearLayout.LayoutParams(0,dp(48),1));
        Button settings = button("Koneksi",this::setup); LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(-2,dp(48)); sp.leftMargin = dp(10); actions.addView(settings,sp); root.addView(actions); gap(root,12);
        connection = label("● Menghubungkan…",12,MINT); root.addView(connection);
        EditText filter = field("Cari chat…",false); filter.setSingleLine(true); root.addView(filter); gap(root,12);
        filter.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s,int a,int c,int f) {}
            public void onTextChanged(CharSequence s,int a,int b,int c) { search = s.toString().toLowerCase(Locale.ROOT); renderThreads(); }
            public void afterTextChanged(Editable e) {}
        });
        search = ""; ScrollView sc = new ScrollView(this); list = column(); sc.addView(list); root.addView(sc,new LinearLayout.LayoutParams(-1,0,1));
        renderThreads(); loadThreads(true);
    }
    void loadThreads(boolean showError) {
        if (polling) return; polling = true; int view = generation;
        io.submit(() -> {
            try {
                JSONObject result = api("GET","/api/threads",null);
                handler.post(() -> { polling = false; if (view != generation || isDestroyed()) return;
                    JSONArray fresh = result.optJSONArray("threads"); if (fresh != null) {
                        // Preserve already paged older chats between list refreshes.
                        LinkedHashMap<String,JSONObject> merged = new LinkedHashMap<>();
                        for (int i=0;i<fresh.length();i++) { JSONObject t=fresh.optJSONObject(i); merged.put(t.optString("id"),t); }
                        for (int i=0;i<threads.length();i++) { JSONObject t=threads.optJSONObject(i); merged.putIfAbsent(t.optString("id"),t); }
                        threads = new JSONArray(merged.values());
                    }
                    if (!pagedThreads) nextThreads = nullable(result,"nextCursor");
                    connection.setText(status.optString("mode").equals("shared") ? "● Terhubung · proses desktop bersama" : "● Terhubung · lanjutan desktop dibuat sebagai salinan");
                    String value = threads.toString(); if (!value.equals(fingerprint)) { fingerprint = value; renderThreads(); }
                });
            } catch (Exception e) { handler.post(() -> { polling=false; if (view!=generation || isDestroyed()) return; connection.setText("● Terputus · mencoba kembali…"); if(showError) error(e.getMessage()); }); }
        });
    }
    void renderThreads() {
        if (list==null || !screen.equals("list")) return; list.removeAllViews(); int count=0;
        for (int i=0;i<threads.length();i++) {
            JSONObject t=threads.optJSONObject(i); String name=t.optString("name","Chat baru");
            if (!name.toLowerCase(Locale.ROOT).contains(search)) continue;
            count++; LinearLayout card=column(); card.setPadding(dp(16),dp(12),dp(16),dp(12)); card.setBackground(background(CARD,16));
            TextView nameView=label(name,16,INK); nameView.setMaxLines(2); nameView.setTypeface(null,Typeface.BOLD); card.addView(nameView);
            String cwd=t.optString("cwd"); card.addView(label(new File(cwd).getName() + (t.optBoolean("forkOnSend") ? "  ·  riwayat desktop" : "  ·  chat remote"),12,MUTED));
            card.setOnClickListener(v -> openChat(t.optString("id"),name,t.optBoolean("forkOnSend")));
            card.setContentDescription("Buka chat " + name); card.setFocusable(true); list.addView(card); gap(list,10);
        }
        if(count==0) list.addView(label("Belum ada chat yang cocok. Mulai dengan Chat baru.",15,MUTED));
        if(!nextThreads.isEmpty()) list.addView(button("Muat chat sebelumnya",() -> {
            String cursor=nextThreads;
            task(() -> api("GET","/api/threads?cursor="+encode(cursor),null), result -> {
                JSONArray more=result.optJSONArray("threads"); if(more!=null) for(int i=0;i<more.length();i++) threads.put(more.get(i));
                pagedThreads=true; nextThreads=nullable(result,"nextCursor"); renderThreads();
            },true);
        }));
    }
    void newChat() {
        JSONArray spaces=status.optJSONArray("workspaces");
        if(spaces==null || spaces.length()==0) { error("Tidak ada folder workspace. Atur REMOTE_CODEX_ROOT pada komputer."); return; }
        String[] names=new String[spaces.length()]; for(int i=0;i<names.length;i++) names[i]=spaces.optJSONObject(i).optString("name");
        new AlertDialog.Builder(this).setTitle("Pilih repo / folder kerja").setItems(names,(dialog,index) -> {
            EditText name=field("Nama chat (opsional)",false); name.setSingleLine(true);
            new AlertDialog.Builder(this).setTitle("Chat baru · "+names[index]).setView(name).setNegativeButton("Batal",null).setPositiveButton("Buat",(d,w) -> {
                task(() -> api("POST","/api/threads",new JSONObject().put("cwd",spaces.getJSONObject(index).getString("path")).put("name",name.getText().toString().trim())), result -> {
                    JSONObject t=result.getJSONObject("thread"); openChat(t.getString("id"),t.getString("name"),false);
                },true);
            }).show();
        }).setNegativeButton("Batal",null).show();
    }
    void openChat(String id,String name,boolean fork) {
        threadId=id; title=name; activeTurn=""; transcript.clear(); sending=false; pagedTurns=false;
        frame("chat"); LinearLayout top=row(); top.addView(button("‹ Chats",this::showThreads));
        TextView brand=label("REMOTE CODEX",12,MINT); brand.setGravity(Gravity.END); brand.setLetterSpacing(.12f); top.addView(brand,new LinearLayout.LayoutParams(0,-2,1)); root.addView(top); gap(root,12);
        TextView titleView=label(name,20,INK); titleView.setMaxLines(2); titleView.setTypeface(null,Typeface.BOLD); root.addView(titleView);
        connection=label("● Terhubung",12,MINT); root.addView(connection);
        if(fork) root.addView(label("Pesan pertama membuat salinan chat ini. Konteks ikut terbawa; chat desktop tetap dikelola desktop.",12,MUTED));
        older=button("Muat pesan sebelumnya",this::loadOlder); older.setVisibility(View.GONE); root.addView(older);
        scroll=new ScrollView(this); scroll.setFillViewport(true); messages=column(); messages.setPadding(0,dp(12),0,dp(12)); scroll.addView(messages); root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        approvalBox=column(); root.addView(approvalBox);
        activity=label("Memuat percakapan…",12,MUTED); root.addView(activity);
        LinearLayout bottom=row(); composer=field("Kirim arahan ke Codex…",false); composer.setMinLines(1); composer.setMaxLines(5); composer.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        bottom.addView(composer,new LinearLayout.LayoutParams(0,-2,1)); send=button("Kirim ↑",this::sendMessage); LinearLayout.LayoutParams bp=new LinearLayout.LayoutParams(-2,dp(52)); bp.leftMargin=dp(8); bottom.addView(send,bp); root.addView(bottom);
        stop=button("Hentikan proses",this::interrupt); stop.setVisibility(View.GONE); root.addView(stop); loadChat(true);
    }
    void loadChat(boolean showError) {
        if(polling) return; polling=true; final String id=threadId; int view=generation;
        io.submit(() -> {
            try { JSONObject result=api("GET","/api/threads/"+encode(id),null);
                handler.post(() -> { polling=false; if(view!=generation || isDestroyed()) return; applySnapshot(result); });
            } catch(Exception e) { handler.post(() -> { polling=false; if(view!=generation || isDestroyed()) return; connection.setText("● Terputus · mencoba kembali…"); if(showError) error(e.getMessage()); }); }
        });
    }
    void applySnapshot(JSONObject result) {
        connection.setText("● Terhubung · " + base);
        JSONArray data=result.optJSONArray("messages"); if(data!=null) for(int i=0;i<data.length();i++) { JSONObject m=data.optJSONObject(i); transcript.put(m.optString("id"),m); }
        String value=transcript.toString(); if(!value.equals(fingerprint)) { fingerprint=value; renderMessages(); }
        if(!pagedTurns) nextTurns=nullable(result,"nextCursor"); older.setVisibility(nextTurns.isEmpty()?View.GONE:View.VISIBLE);
        activeTurn=nullable(result,"activeTurnId"); stop.setVisibility(activeTurn.isEmpty()?View.GONE:View.VISIBLE); send.setEnabled(activeTurn.isEmpty() && !sending);
        String err=nullable(result,"error"); activity.setText(!err.isEmpty()?"Gagal: "+err:activeTurn.isEmpty()?"Siap menerima arahan":"Codex sedang bekerja…");
        JSONArray pending=result.optJSONArray("approvals"); String approvals=pending==null?"":pending.toString();
        if(!approvals.equals(approvalFingerprint)) { approvalFingerprint=approvals; renderApprovals(pending); }
    }
    void renderMessages() {
        boolean atBottom=scroll.getChildCount()==0 || scroll.getScrollY()+scroll.getHeight()>=messages.getHeight()-dp(100);
        int previous=scroll.getScrollY(); messages.removeAllViews();
        for(JSONObject m:transcript.values()) {
            String role=m.optString("role"),content=m.optString("text"); if(content.isEmpty()) continue;
            LinearLayout card=column(); card.setPadding(dp(14),dp(10),dp(14),dp(10)); card.setBackground(background(role.equals("user")?Color.rgb(29,57,53):CARD,14));
            TextView who=label(role.equals("user")?"KAMU":role.equals("tool")?"AKTIVITAS":"CODEX",10,role.equals("user")?MINT:MUTED); who.setLetterSpacing(.14f); card.addView(who);
            TextView text=label(content,role.equals("tool")?12:15,INK); text.setTextIsSelectable(true); text.setLineSpacing(dp(3),1); if(role.equals("tool")) text.setTypeface(Typeface.MONOSPACE); card.addView(text); messages.addView(card); gap(messages,12);
        }
        if(transcript.isEmpty()) messages.addView(label("Mulai percakapan. Misalnya: “Cek repo ini dan jelaskan strukturnya.”",16,MUTED));
        scroll.post(() -> { if(atBottom) scroll.fullScroll(View.FOCUS_DOWN); else scroll.scrollTo(0,previous); });
    }
    void loadOlder() {
        final String id=threadId,cursor=nextTurns;
        task(() -> api("GET","/api/threads/"+encode(id)+"?cursor="+encode(cursor),null), result -> {
            LinkedHashMap<String,JSONObject> merged=new LinkedHashMap<>(); JSONArray data=result.getJSONArray("messages");
            for(int i=0;i<data.length();i++) { JSONObject m=data.getJSONObject(i); merged.put(m.getString("id"),m); }
            merged.putAll(transcript); transcript.clear(); transcript.putAll(merged); pagedTurns=true; nextTurns=nullable(result,"nextCursor"); older.setVisibility(nextTurns.isEmpty()?View.GONE:View.VISIBLE); renderMessages();
        },true);
    }
    void sendMessage() {
        final String input=composer.getText().toString().trim(); if(input.isEmpty() || sending) return;
        if(input.length()>32000) { error("Pesan maksimal 32.000 karakter."); return; }
        if(!input.equals(retryText)) { retryText=input; retryId=UUID.randomUUID().toString(); }
        final String id=threadId,clientId=retryId; int view=generation; sending=true; send.setEnabled(false); activity.setText("Mengirim…");
        io.submit(() -> {
            try { JSONObject result=api("POST","/api/threads/"+encode(id)+"/messages",new JSONObject().put("text",input).put("clientMessageId",clientId));
                handler.post(() -> { sending=false; if(view!=generation || isDestroyed()) return; composer.setText(""); retryText=""; retryId="";
                    String target=result.optString("threadId",id); if(!target.equals(id)) { openChat(target,"HP · "+title,false); return; }
                    activeTurn=result.optString("turnId"); stop.setVisibility(View.VISIBLE); activity.setText("Codex sedang bekerja…"); loadChat(false);
                });
            } catch(Exception e) { handler.post(() -> { sending=false; if(view!=generation || isDestroyed()) return; send.setEnabled(true); activity.setText("Belum terkonfirmasi. Periksa percakapan sebelum mengirim ulang."); error(e.getMessage()); }); }
        });
    }
    void interrupt() {
        final String id=threadId,turn=activeTurn;
        task(() -> api("POST","/api/threads/"+encode(id)+"/interrupt",new JSONObject().put("turnId",turn)), result -> loadChat(false),true);
    }
    void renderApprovals(JSONArray pending) {
        approvalBox.removeAllViews(); if(pending==null) return;
        for(int i=0;i<pending.length();i++) {
            JSONObject request=pending.optJSONObject(i),params=request.optJSONObject("params"); if(params==null) continue;
            LinearLayout card=column(); card.setPadding(dp(12),dp(8),dp(12),dp(8)); card.setBackground(background(Color.rgb(55,43,25),12));
            card.addView(label("PERLU JAWABANMU",11,Color.rgb(255,208,131)));
            if(request.optString("method").equals("item/tool/requestUserInput")) {
                card.addView(button("Jawab pertanyaan Codex",() -> answerQuestions(request)));
            } else {
                TextView preview=label(params.optString("command",params.optString("reason","Persetujuan perubahan file")),12,INK); preview.setMaxLines(4); card.addView(preview);
                card.addView(button("Tinjau permintaan",() -> {
                    String details; try { details=request.toString(2); } catch(Exception e) { details=request.toString(); }
                    TextView detail=label(details,13,INK); detail.setPadding(dp(20),dp(12),dp(20),dp(12)); detail.setTextIsSelectable(true); ScrollView sc=new ScrollView(this); sc.addView(detail);
                    new AlertDialog.Builder(this).setTitle("Setujui sekali?").setView(sc).setPositiveButton("Setujui",(d,w) -> decide(request,"accept"))
                        .setNegativeButton("Tolak",(d,w) -> decide(request,"decline")).setNeutralButton("Kembali",null).show();
                }));
            }
            approvalBox.addView(card);
        }
    }
    void decide(JSONObject request,String decision) {
        final String id=threadId;
        task(() -> api("POST","/api/threads/"+encode(id)+"/approvals",new JSONObject().put("id",request.get("id")).put("decision",decision)), result -> loadChat(false),true);
    }
    void answerQuestions(JSONObject request) {
        JSONArray questions=request.optJSONObject("params").optJSONArray("questions"); if(questions==null) return;
        LinearLayout form=column(); form.setPadding(dp(20),dp(12),dp(20),dp(12)); Map<String,EditText> answers=new LinkedHashMap<>();
        for(int i=0;i<questions.length();i++) {
            JSONObject q=questions.optJSONObject(i); form.addView(label(q.optString("question"),15,INK));
            EditText answer=field("Jawaban",q.optBoolean("isSecret")); answers.put(q.optString("id"),answer);
            JSONArray options=q.optJSONArray("options"); if(options!=null) for(int j=0;j<options.length();j++) {
                JSONObject option=options.optJSONObject(j); String choice=option.optString("label"); form.addView(button(choice,() -> answer.setText(choice)));
            }
            form.addView(answer); gap(form,12);
        }
        ScrollView sc=new ScrollView(this); sc.addView(form); final String id=threadId;
        new AlertDialog.Builder(this).setTitle("Pertanyaan dari Codex").setView(sc).setNegativeButton("Kembali",null).setPositiveButton("Kirim jawaban",(d,w) -> {
            JSONObject values=new JSONObject(); try { for(Map.Entry<String,EditText> entry:answers.entrySet()) values.put(entry.getKey(),entry.getValue().getText().toString()); } catch(JSONException ignored) {}
            task(() -> api("POST","/api/threads/"+encode(id)+"/approvals",new JSONObject().put("id",request.get("id")).put("answers",values)),result -> loadChat(false),true);
        }).show();
    }
    static String nullable(JSONObject o,String key) { return o.isNull(key)?"":o.optString(key,""); }
    static String encode(String value) {
        try { return URLEncoder.encode(value,"UTF-8"); } catch(UnsupportedEncodingException e) { throw new AssertionError(e); }
    }
    JSONObject api(String method,String path,JSONObject data) throws Exception { return request(base,token,method,path,data); }
    static void validateAddress(String value) throws Exception {
        URI uri=new URI(value);
        if(uri.getHost()==null || uri.getUserInfo()!=null || uri.getQuery()!=null || uri.getFragment()!=null || (uri.getPath()!=null && !uri.getPath().isEmpty())) throw new IOException("Alamat harus berupa http(s)://host:port tanpa path.");
        if("https".equals(uri.getScheme())) return;
        if(!"http".equals(uri.getScheme())) throw new IOException("Gunakan HTTP jaringan privat atau HTTPS.");
        for(InetAddress address:InetAddress.getAllByName(uri.getHost())) {
            byte[] bytes=address.getAddress();
            boolean tailscale=bytes.length==4 && (bytes[0]&255)==100 && (bytes[1]&255)>=64 && (bytes[1]&255)<=127;
            if(!address.isSiteLocalAddress() && !address.isLoopbackAddress() && !tailscale) throw new IOException("HTTP hanya untuk IP privat / Tailscale. Gunakan HTTPS untuk host publik.");
        }
    }
    static JSONObject request(String base,String token,String method,String path,JSONObject data) throws Exception {
        validateAddress(base); HttpURLConnection connection=(HttpURLConnection)new URL(base+path).openConnection();
        connection.setInstanceFollowRedirects(false); connection.setConnectTimeout(8000); connection.setReadTimeout(65000); connection.setRequestMethod(method);
        connection.setRequestProperty("Authorization","Bearer "+token); connection.setRequestProperty("Accept","application/json");
        try {
            if(data!=null) { connection.setDoOutput(true); connection.setRequestProperty("Content-Type","application/json"); try(OutputStream out=connection.getOutputStream()) { out.write(data.toString().getBytes(StandardCharsets.UTF_8)); } }
            int code=connection.getResponseCode(); if(code>=300 && code<400) throw new IOException("Redirect ditolak. Gunakan alamat bridge secara langsung.");
            InputStream input=code>=400?connection.getErrorStream():connection.getInputStream();
            if(input==null) throw new IOException("Bridge tidak memberi respons (HTTP "+code+").");
            String raw; try(input; ByteArrayOutputStream out=new ByteArrayOutputStream()) {
                byte[] buffer=new byte[8192]; int count; while((count=input.read(buffer))!=-1) { if(out.size()+count>8000000) throw new IOException("Riwayat terlalu besar."); out.write(buffer,0,count); } raw=out.toString(StandardCharsets.UTF_8.name());
            }
            JSONObject result; try { result=new JSONObject(raw); } catch(JSONException e) { throw new IOException("Respons bukan JSON. Periksa alamat bridge."); }
            if(code>=400) throw new IOException(result.optString("error","HTTP "+code)); return result;
        } finally { connection.disconnect(); }
    }
    SecretKey secretKey() throws Exception {
        KeyStore store=KeyStore.getInstance("AndroidKeyStore"); store.load(null);
        if(store.containsAlias("remote_codex_pairing")) return (SecretKey)store.getKey("remote_codex_pairing",null);
        KeyGenerator generator=KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore");
        generator.init(new KeyGenParameterSpec.Builder("remote_codex_pairing",KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT).setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build());
        return generator.generateKey();
    }
    String encrypt(String value) throws Exception {
        Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding"); cipher.init(Cipher.ENCRYPT_MODE,secretKey());
        return Base64.encodeToString(cipher.getIV(),Base64.NO_WRAP)+":"+Base64.encodeToString(cipher.doFinal(value.getBytes(StandardCharsets.UTF_8)),Base64.NO_WRAP);
    }
    String decrypt(String value) throws Exception {
        if(value.isEmpty()) return ""; String[] parts=value.split(":"); Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE,secretKey(),new GCMParameterSpec(128,Base64.decode(parts[0],Base64.NO_WRAP)));
        return new String(cipher.doFinal(Base64.decode(parts[1],Base64.NO_WRAP)),StandardCharsets.UTF_8);
    }
}
