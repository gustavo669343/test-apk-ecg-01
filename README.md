# 🩺 ECG & Vital Signs AI Monitor (Android / Jetpack Compose)
## Tích hợp On-Device TensorFlow Lite & Tương thích Toàn diện Hệ thống Vi điều khiển ESP32-S3

Ứng dụng di động Android hiện đại được xây dựng bằng **Kotlin**, **Jetpack Compose (Material 3)**, **Coroutines/Flow**, **TensorFlow Lite On-Device** và **Room Database** để kết nối, nhận dữ liệu thời gian thực và giám sát toàn diện hệ thống **ESP32-S3 ECG & Sinh hiệu Đa thông số AI**.

---

### 🌟 Điểm nổi bật & Tính năng mới (Bản cập nhật v2.0 Dual AI)

1. **Kiến trúc Dual AI Engine (Chuyển đổi linh hoạt Toggle)**:
   - **Nguồn 1 - ESP32 Hardware AI**: Tiếp nhận kết quả phân loại từ mô hình CNN 3-nhánh chạy trên vi điều khiển qua gói tin BLE.
   - **Nguồn 2 - Android On-Device TFLite AI**: Chạy suy luận trực tiếp trên điện thoại bằng các mô hình định lượng INT8 siêu nhẹ, độc lập với vi điều khiển.
   - Người dùng có thể dễ dàng chuyển đổi nguồn hiển thị trên UI bằng 1 cú chạm.

2. **Tích hợp Top 5 Mô hình AI Benchmark từ MATLAB (`assets/models/`)**:
   - ⚡ **`kachuee_residual_int8.tflite`** (187 mẫu @ 125Hz): Mạng Deep Residual 5 khối (Kachuee et al., 2018), độ chính xác **99.21%**, Macro F1 **0.9418**, dung lượng 90.2 KB.
   - 🥇 **`model_3_branch_f32_leaky_int8.tflite`** (375 mẫu @ 125Hz): Top 1 Toàn diện MaxPool, Macro F1 **0.9401**, dung lượng 42.6 KB.
   - ⚙️ **`model_4_branch_f16_relu_int8.tflite`** (375 mẫu @ 125Hz): Chuẩn TinyML cân bằng tối ưu giữa độ chính xác và tài nguyên, F1 **0.9299**, dung lượng 34.8 KB.
   - 🪶 **`model_4_branch_f8_relu_int8.tflite`** (375 mẫu @ 125Hz): Quán quân siêu nhẹ chỉ 27.9 KB, suy luận thần tốc dưới 3 ms.
   - 🌊 **`model_3_branch_f32_relu_avgpool_int8.tflite`** (375 mẫu @ 125Hz): Top 1 AveragePooling làm mượt đặc trưng, S-beat F1 **90.85%**.

3. **Đồng bộ Chuỗi Tiền xử lý & Cắt nhịp 1:1 từ MATLAB**:
   - **Dò đỉnh Pan-Tompkins thời gian thực (`PanTompkinsDetector.kt`)**: Khớp 1:1 với `pan_tompkins_qrs.m` (Bandpass HP 0.95 + LP 0.15, vi phân trễ 2 mẫu, cửa sổ MWI 18 mẫu, ngưỡng động thích nghi $Th=30$, tinh chỉnh cực trị đỉnh R trong $[-15, +5]$ mẫu).
   - **Cắt nhịp thích ứng (`EcgSegmenter.kt`)**: Khớp 1:1 với `segment_heartbeats.m` (tự động cắt 375 mẫu hoặc 187 mẫu theo mô hình được chọn và chuẩn hóa Min-Max $[0.0, 1.0]$).

4. **Chế độ Chạy thử Ngoại tuyến (Demo Simulation Mode)**:
   - Cho phép người dùng bật/tắt nhanh chế độ Demo (`🧪 Demo`) ngay trên thanh tiêu đề hoặc Cài đặt.
   - Tự động phát sóng ECG giả lập tần số 125 Hz (gồm cả nhịp bình thường và nhịp ngoại tâm thất PVC định kỳ), chạy qua Pan-Tompkins và kích hoạt On-Device TFLite để kiểm thử toàn diện mọi tính năng mà không cần phần cứng ESP32.

5. **Đồng bộ chuẩn giao thức BLE với `ecg_monitor_ai`**:
   - **Tên thiết bị nhận diện**: `ESP32_ECG_AI_Tester`.
   - **GATT Service UUID**: `4fafc201-1fb5-459e-8fcc-c5c9c331914b`.
   - **GATT Characteristic UUID**: `beb5483e-36e1-4688-b7f5-ea07361b26a8`.
   - **Cơ chế phân giải chuỗi (ASCII Protocol)**: Tự động trích xuất các gói tin ở tần số 125 Hz:
     `R:<raw>|F:<filtered>|P:<0/1>|B:<bpm>|C:<class>|CN:<name>|Q:<p0,p1,p2,p3,p4>|SPO2:<spo2>|PBPM:<ppg>|IR:<ir>`

6. **Phân tích & So sánh Đa Đồ thị ECG (3 Kênh thời gian thực)**:
   - **Đồ thị 1 (Tín hiệu thô - Raw)**: Vẽ trực tiếp giá trị ADC thô từ cảm biến AD8232.
   - **Đồ thị 2 (Lọc từ ESP32)**: Tín hiệu đã lọc thời gian thực trên chip ESP32-S3 (Notch 50 Hz + chống trôi nền + làm mượt).
   - **Đồ thị 3 (Lọc trên Android)**: Tín hiệu chạy qua bộ lọc số `RealtimeEcgFilter` cục bộ của Android để đối chiếu và kiểm chứng chất lượng bộ lọc vi điều khiển.

7. **Giám sát Sinh hiệu Toàn diện (MAX30102)**:
   - Nồng độ oxy trong máu **SpO₂ (%)**.
   - Nhịp mạch quang học **PPG Pulse (BPM)**.
   - Phát hiện nhịp tim đập thời gian thực (Heart Beat indicator ❤️).

8. **Linh vật Ami Mascot tương tác động**:
   - Tự động thay đổi biểu cảm GIF sinh động theo trạng thái kết nối và sức khỏe:
     - *Chưa kết nối*: `ami_sleep.gif` (ngủ)
     - *Đang quét / Kết nối*: `ami_question.gif` (thắc mắc)
     - *Nhịp bình thường*: `ami_happy.gif` (lấp lánh) hoặc `ami_nod.gif` (gật đầu)
     - *Loạn nhịp / Nhịp bất thường*: `ami_crying.gif` (khóc / lo lắng)

9. **Lưu trữ Cục bộ (Room Database)**:
   - Lưu trữ các phiên đo ECG vào SQLite cục bộ qua Room DB (`ecg_sessions`).
   - Phân tích và biểu diễn xu hướng nhịp tim trung bình trong 7 ngày gần nhất.

10. **Xuất Báo cáo Đo ra Tệp .CSV (Tự chọn Vị trí lưu)**:
    - Xuất dữ liệu toàn bộ phiên đo chuẩn 100% định dạng `in/data`:
      `Index,Raw,Filtered,Peak,BPM,Class_ID,Class_Name,P0,P1,P2,P3,P4`
    - Tự động gợi ý tên tệp dạng `ble_received_ecg_YYYYMMDD_HHmmss.csv`.
    - Hộp thoại Storage Access Framework (SAF) cho phép người dùng tự chọn thư mục lưu trữ (Download, Documents, Thẻ nhớ SD, Google Drive...) mà không cần quyền nguy hiểm.

---

### 🚀 Cách 1: Tự động Build APK trên Cloud qua GitHub Actions (0 MB trên máy)

Dự án đã được tích hợp sẵn workflow tại `.github/workflows/build_apk.yml`. Bạn chỉ cần đẩy code lên GitHub theo các bước:

1. **Khởi tạo và đẩy code lên GitHub**:
   Mở terminal tại thư mục `out/ecg_android_ai` và chạy:
   ```bash
   git add .
   git commit -m "feat: Add On-Device TFLite inference, Pan-Tompkins detector and Top 5 AI models"
   git push -u origin main
   ```

2. **Tải file APK về điện thoại**:
   - Truy cập vào trang Repository trên GitHub của bạn.
   - Nhấn vào tab **Actions**.
   - Chọn lần chạy mới nhất (workflow **Build Android ECG AI APK**).
   - Đợi khoảng 2–3 phút khi có dấu tích xanh ✅, cuộn xuống phần **Artifacts** và bấm tải **`ECG-AI-Monitor-Debug-APK`**.
   - Giải nén file zip tải về sẽ có file `app-debug.apk` để cài đặt trực tiếp lên điện thoại!

---

### 💻 Cách 2: Mở và chạy dự án cục bộ trong Android Studio

1. **Mở dự án**:
   - Mở Android Studio, chọn **Open** và dẫn tới thư mục `out/ecg_android_ai`.
2. **Cấu hình môi trường**:
   - **JDK**: Java 17.
   - **Android SDK**: Compile SDK 35 (hỗ trợ Android 8.0 / API 26 trở lên).
   - Chọn **Sync Project with Gradle Files**.
3. **Chạy ứng dụng**:
   - Kết nối điện thoại Android qua USB (bật chế độ Developer Options & USB Debugging) hoặc chạy trên Emulator.
   - Bật Bluetooth và cấp quyền vị trí / thiết bị ở gần khi mở app.
   - Nhấn **Run 'app'** (Shift + F10).

---

### 📂 Cấu trúc mã nguồn chính

- `app/src/main/java/com/example/ecgmonitor/`:
  - `MainActivity.kt`: Giao diện Jetpack Compose (HomeScreen với bộ chọn Dual AI, DetailScreen 3 đồ thị, TrendsScreen, SettingsScreen) và Renderer GIF động cho Ami.
  - `EcgViewModel.kt`: Quản lý luồng gói tin, chuyển đổi nguồn AI (ESP32 vs Android), bộ đệm sóng, điều khiển biểu cảm mascot Ami, và ghi Room DB.
  - `ble/BleEcgClient.kt`: GATT Client quét và kết nối với `ESP32_ECG_AI_Tester`, bộ parser chuỗi ASCII thời gian thực 125 Hz.
  - `domain/TfliteModelMeta.kt`: Định nghĩa thông tin, siêu tham số và danh mục 5 mô hình Top Benchmark.
  - `domain/TfliteEcgClassifier.kt`: Quản lý vòng đời TensorFlow Lite Interpreter, nạp model INT8 từ assets và suy luận phân loại 5 lớp AAMI.
  - `domain/PanTompkinsDetector.kt`: Thuật toán dò đỉnh QRS Pan-Tompkins thời gian thực đồng bộ 1:1 từ MATLAB.
  - `domain/EcgSegmenter.kt`: Cắt nhịp tim thích ứng kích thước và chuẩn hóa Min-Max $[0.0, 1.0]$.
  - `domain/RealtimeEcgFilter.kt`: Bộ lọc số IIR cục bộ trên Android.
  - `domain/EcgDemoProcessor.kt`: Bộ phát sóng ECG giả lập 125 Hz ngoại tuyến cho chế độ Demo.
  - `domain/EcgAnalysis.kt`: Ước tính BPM và đưa ra lời khuyên sức khoẻ.
  - `data/EcgDatabase.kt`: Bảng Room Database `ecg_sessions`.
- `app/src/main/assets/models/`: Chứa 5 file mô hình `.tflite` INT8 tối ưu hóa cho Edge AI.
- `app/src/main/assets/`: 17 tệp GIF biểu cảm linh vật Ami.
