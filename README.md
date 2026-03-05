# 📹 Remote Camera IP

Ứng dụng Java xem camera từ xa qua mạng. Camera remote xuất hiện như **webcam thật** → dùng trên **Google Meet, Zoom, Teams**.

## 🚀 Cài đặt

### Yêu cầu

- **Java JDK 17+** ([download](https://adoptium.net/))
- **Gradle** ([download](https://gradle.org/install/)) hoặc có sẵn wrapper
- **Python 3** + `pyvirtualcam` (chỉ cần cho Virtual Camera)
- **OBS Studio** (chỉ cần cho Virtual Camera driver)

### Setup nhanh

```batch
cd "d:\DuAn\camera ip"
setup.bat
```

## 📖 Cách dùng

### 1. PC có camera → Chạy Camera Host

```batch
gradlew runHost
```

- Chọn camera, resolution, quality
- Bấm **"Bắt đầu Stream"**
- Ghi nhớ **IP Address** hiển thị (LAN hoặc ZeroTier)

### 2. PC không camera → Chạy Camera Client

```batch
gradlew runClient
```

- Nhập **IP** của Camera Host + **Port** (default: 9000)
- Bấm **"Kết nối"** → xem video real-time
- Bấm **"Bật Virtual Camera"** → camera remote xuất hiện trong Google Meet

### 3. Google Meet / Zoom

- Vào Settings → Camera → chọn **"OBS Virtual Camera"**
- Video từ camera remote sẽ hiện!

## 🌐 Dùng qua Internet (ZeroTier)

1. Cài ZeroTier trên **cả 2 PC**: https://www.zerotier.com/download/
2. Tạo network tại https://my.zerotier.com/ → lấy **Network ID**
3. Cả 2 PC join cùng Network ID
4. Ở Camera Client, nhập **ZeroTier IP** của Host (hiển thị trong app)

## 📁 Cấu trúc

```
camera-ip/
├── build.gradle                    # Gradle build config
├── setup.bat                       # One-time setup
├── vcam_bridge.py                  # Virtual camera bridge (Python)
├── src/main/java/com/cameraip/
│   ├── CameraHostApp.java          # Camera Host GUI + server
│   └── CameraClientApp.java        # Camera Client GUI + viewer
└── README.md
```
