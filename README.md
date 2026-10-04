# 🩺 ECG & Vital Signs AI Monitor (Android / Jetpack Compose)
## Tương thích toàn diện với Hệ thống Vi điều khiển ESP32-S3 (`ecg_monitor_ai`)

Ứng dụng di động Android hiện đại được xây dựng bằng **Kotlin**, **Jetpack Compose (Material 3)**, **Coroutines/Flow** và **Room Database** để kết nối, nhận dữ liệu thời gian thực và giám sát toàn diện hệ thống **ESP32-S3 ECG & Sinh hiệu Đa thông số AI**.

---

### 🌟 Điểm nổi bật & Tính năng mới

1. **Đồng bộ chuẩn giao thức BLE với `ecg_monitor_ai`**:
   - **Tên thiết bị nhận diện**: `ESP32_ECG_AI_Tester`.
   - **GATT Service UUID**: `4fafc201-1fb5-459e-8fcc-c5c9c331914b`.
   - **GATT Characteristic UUID**: `beb5483e-36e1-4688-b7f5-ea07361b26a8`.
   - **Cơ chế phân giải chuỗi (ASCII Protocol)**: Tự động trích xuất các gói tin ở tần số 125 Hz:
     `R:<raw>|F:<filtered>|P:<0/1>|B:<bpm>|C:<class>|CN:<name>|Q:<p0,p1,p2,p3,p4>|SPO2:<spo2>|PBPM:<ppg>|IR:<ir>`
   - Hỗ trợ cơ chế dự phòng (Fallback) tự động nhận diện gói tin nhị phân nếu cần.

2. **Phân tích & So sánh Đa Đồ thị ECG (3 Panels)**:
   - **Đồ thị 1 (Tín hiệu thô - Raw)**: Vẽ trực tiếp giá trị ADC thô từ cảm biến AD8232.
   - **Đồ thị 2 (Lọc từ ESP32)**: Tín hiệu đã lọc thời gian thực trên chip ESP32-S3 (Notch 50 Hz + chống trôi nền + làm mượt).
   - **Đồ thị 3 (Lọc trên Android)**: Tín hiệu chạy qua bộ lọc số `RealtimeEcgFilter` cục bộ của Android để đối chiếu và kiểm chứng chất lượng bộ lọc vi điều khiển.

3. **Chẩn đoán Edge AI 5 lớp chuẩn AAMI / MIT-BIH**:
   - Nhận diện nhịp tim phân loại trực tiếp từ mạng nơ-ron CNN 3 nhánh trên ESP32:
     - **N**: Normal Beat (Nhịp xoang bình thường)
     - **S**: Supraventricular Ectopic Beat (Ngoại tâm thu trên thất)
     - **V**: Ventricular Ectopic Beat / PVC (Ngoại tâm thu thất)
     - **F**: Fusion Beat (Nhịp kết hợp)
     - **Q**: Paced / Unknown Beat (Nhịp máy tạo nhịp / Không xác định)
   - Hiển thị trực quan xác suất % của cả 5 lớp.

4. **Giám sát Sinh hiệu Toàn diện (MAX30102)**:
   - Nồng độ oxy trong máu **SpO₂ (%)**.
   - Nhịp mạch quang học **PPG Pulse (BPM)**.
   - Phát hiện nhịp tim đập thời gian thực (Heart Beat indicator).

5. **Linh vật Ami Mascot tương tác động**:
   - Tự động thay đổi biểu cảm GIF sinh động theo trạng thái kết nối và sức khỏe:
     - *Chưa kết nối*: `ami_sleep.gif` (ngủ)
     - *Đang quét / Kết nối*: `ami_question.gif` (thắc mắc)
     - *Nhịp bình thường*: `ami_happy.gif` (lấp lánh) hoặc `ami_nod.gif` (gật đầu)
     - *Loạn nhịp / Nhịp bất thường*: `ami_crying.gif` (khóc / lo lắng)

6. **Lưu trữ Cục bộ (Room Database)**:
   - Lưu trữ các phiên đo ECG vào SQLite cục bộ qua Room DB (`ecg_sessions`).
   - Phân tích và biểu diễn xu hướng nhịp tim trung bình trong 7 ngày gần nhất.

---

### 🚀 Cách 1: Tự động Build APK trên Cloud qua GitHub Actions (0 MB trên máy)

Dự án đã được tích hợp sẵn workflow tại `.github/workflows/build_apk.yml`. Bạn chỉ cần đẩy code lên GitHub theo các bước:

1. **Khởi tạo và đẩy code lên GitHub**:
   Mở terminal tại thư mục `out/ecg_android_ai` và chạy:
   ```bash
   git init
   git add .
   git commit -m "feat: ECG AI Android Companion for ESP32-S3"
   git branch -M main
   git remote add origin <URL_REPOSITORY_GITHUB_CUA_BAN>
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
  - `MainActivity.kt`: Toàn bộ giao diện Jetpack Compose (4 tabs: Trang chủ, Đồ thị so sánh, Xu hướng, Cài đặt) và Renderer GIF động cho Ami.
  - `EcgViewModel.kt`: Quản lý luồng gói tin, 3 bộ đệm sóng, cập nhật sinh hiệu, điều khiển biểu cảm mascot Ami, và ghi Room DB.
  - `ble/BleEcgClient.kt`: GATT Client quét và kết nối với `ESP32_ECG_AI_Tester`, bộ parser chuỗi ASCII thời gian thực 125 Hz.
  - `domain/RealtimeEcgFilter.kt`: Bộ lọc số IIR cục bộ trên Android.
  - `domain/EcgAnalysis.kt`: Thuật toán ước tính BPM và lời khuyên sức khoẻ kết hợp AI.
  - `data/EcgDatabase.kt`: Định nghĩa bảng Room Database `ecg_sessions`.
- `app/src/main/assets/`: 17 tệp GIF biểu cảm linh vật Ami.
