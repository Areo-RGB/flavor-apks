# Race Timing WiFi Network Setup Guide

## Overview

This guide configures a TP-Link TL-WR902AC router as a dedicated local WiFi network for SprintSync race timing. All devices will have static IP addresses for instant, low-latency communication without discovery delays.

---

## Hardware Requirements

- **TP-Link TL-WR902AC** (or similar travel router)
- **5 Android devices** for race timing:
  - 1x Host device (192.168.0.10)
  - 1x Start device (192.168.0.11)
  - 1x Stop device (192.168.0.12)
  - 1x Display device (192.168.0.13)
  - 1x Split device (192.168.0.14) - optional
- Power bank or USB power for router

---

## Step 1: Router Physical Setup

1. **Power on the router**
   - Connect to power bank via Micro USB
   - Wait for LED to stabilize (solid blue)

2. **Set the mode switch to "host"**
   - This creates a standalone WiFi access point
   - Position: middle of the 3-way switch

---

## Step 2: Initial Router Configuration

### 2.1 Connect to Router Admin

1. **Connect your laptop/phone to router WiFi**
   - SSID: `TP-Link_XXXX_5G` (shown on router label)
   - Password: `XXXXXX` (shown on router label)

2. **Open browser and go to:**
   - `http://192.168.0.1` or `http://tplinkwifi.net`

3. **Login with default credentials:**
   - Username: `admin`
   - Password: `admin` (or check router label)

### 2.2 Quick Setup Wizard

1. Select **"Wireless Router"** as operation mode
2. Skip internet connection (leave as Dynamic IP)
3. Set admin password (remember this!)

---

## Step 3: Configure WiFi Network

### 3.1 Basic Wireless Settings

Navigate to: **Wireless → Wireless Settings**

**5GHz Band (recommended for race timing):**
- Wireless Network Name (SSID): `SprintRace`
- Mode: `11a/n/ac mixed`
- Channel: `44` (or `Auto`)
- Channel Width: `Auto`
- Enable SSID Broadcast: ✓ Checked

**2.4GHz Band (optional backup):**
- Wireless Network Name (SSID): `SprintRace_2G`
- Mode: `11bgn mixed`
- Channel: `6`

**Click Save**

### 3.2 Wireless Security

Navigate to: **Wireless → Wireless Security**

**5GHz Security:**
- Security Mode: `WPA/WPA2 - Personal`
- Version: `WPA2-PSK` (or Auto)
- Encryption: `AES`
- Wireless Password: `race2024` (or your preferred password)

**2.4GHz Security:**
- Same settings as 5GHz
- Same password

**Click Save**

---

## Step 4: Configure Static IP Addresses

### 4.1 Assign Static IPs via DHCP

Navigate to: **DHCP → Address Reservation** (or **DHCP Clients List**)

**Method 1: Using Connected Devices**

1. Connect all 5 race devices to `SprintRace` WiFi
2. Go to **DHCP Clients List**
3. You should see connected devices with their MAC addresses
4. For each device, click "Reserve" or "Add Reservation"

**Method 2: Manual Entry**

Navigate to: **DHCP → Address Reservation → Add New**

Enter these reservations:

| Device Role | MAC Address | Reserved IP | Description |
|-------------|-------------|-------------|-------------|
| Host | XX:XX:XX:XX:XX:01 | 192.168.0.10 | Race Host |
| Start | XX:XX:XX:XX:XX:02 | 192.168.0.11 | Start Sensor |
| Stop | XX:XX:XX:XX:XX:03 | 192.168.0.12 | Stop Sensor |
| Display | XX:XX:XX:XX:XX:04 | 192.168.0.13 | Display Client |
| Split | XX:XX:XX:XX:XX:05 | 192.168.0.14 | Split Sensor |

**To find MAC address on Android:**
- Settings → About Phone → Status → Wi-Fi MAC address

**Click Save**

### 4.2 Configure DHCP Range

Navigate to: **DHCP → DHCP Settings**

- DHCP Server: `Enabled`
- Start IP Address: `192.168.0.100`
- End IP Address: `192.168.0.200`
- Default Gateway: `192.168.0.1`
- Primary DNS: `192.168.0.1`
- Lease Time: `2880` minutes (48 hours)

**Why this range?**
- Static IPs: 192.168.0.10-192.168.0.50 (reserved for race devices)
- Dynamic pool: 192.168.0.100-192.168.0.200 (guests/backup devices)

**Click Save**

---

## Step 5: Disable Unnecessary Features

### 5.1 Disable AP Isolation (Critical!)

Navigate to: **Wireless → Wireless Settings → Advanced**

- **AP Isolation**: `Disabled` (must be OFF for device-to-device communication)

This allows your race devices to talk directly to each other without going through the internet.

### 5.2 Disable IGMP Snooping (Optional)

Navigate to: **Network → Advanced Routing** or **Switch → IGMP Snooping**

- IGMP Snooping: `Disabled` (unless you need multicast)

### 5.3 Disable Guest Network Isolation

If using guest networks, ensure:
- Access Intranet: `Enabled`

---

## Step 6: Verify Configuration

### 6.1 Check All Settings

1. **Reboot router**: System Tools → Reboot
2. **Wait 30 seconds** for full startup

### 6.2 Test Connectivity

1. Connect Host device to `SprintRace`
2. Check IP: Settings → About → Status → IP Address
   - Should show: `192.168.0.10`
3. Connect Start device
   - Should show: `192.168.0.11`
4. Repeat for all devices

### 6.3 Test Device-to-Device Communication

**On Host device (192.168.0.10):**
```bash
# Install Termux or use ADB shell
ping 192.168.0.11  # Should respond < 5ms
ping 192.168.0.12  # Should respond < 5ms
ping 192.168.0.13  # Should respond < 5ms
```

**Expected latency:** 1-5ms between devices

---

## Step 7: SprintSync App Configuration

### 7.1 Update App for Static IP Mode

Currently SprintSync uses Google Nearby Connections which auto-discovers devices. For static IP mode, you need to:

**Option A: Keep Nearby Connections (Recommended)**
- Just connect all devices to `SprintRace` WiFi
- Nearby will work faster since all devices are on low-latency LAN
- No code changes needed

**Option B: Direct TCP Socket Mode (Fastest)**
- Modify app to use direct TCP sockets instead of Nearby
- Hardcode IP addresses per device role
- Instant connection, no discovery delay

### 7.2 Label Your Devices

**Physical labels on each device:**

| Device | IP Address | Role |
|--------|-----------|------|
| Tablet 1 | 192.168.0.10 | HOST |
| Phone 1 | 192.168.0.11 | START |
| Phone 2 | 192.168.0.12 | STOP |
| Tablet 2 | 192.168.0.13 | DISPLAY |
| Phone 3 | 192.168.0.14 | SPLIT (opt) |

---

## Step 8: Race Day Checklist

### Pre-Race Setup (30 minutes before)

- [ ] Power on router (power bank)
- [ ] Verify router LED is solid blue
- [ ] Connect Host device → verify IP 192.168.0.10
- [ ] Connect Start device → verify IP 192.168.0.11
- [ ] Connect Stop device → verify IP 192.168.0.12
- [ ] Connect Display → verify IP 192.168.0.13
- [ ] Open SprintSync on all devices
- [ ] Host: Start hosting
- [ ] Clients: Join race session
- [ ] Test trigger: Start → Stop (should sync)

### Troubleshooting

**Device gets wrong IP:**
1. Settings → WiFi → Forget `SprintRace`
2. Reconnect
3. Check IP again

**High latency (>10ms):**
1. Move devices closer to router
2. Switch to 5GHz if on 2.4GHz
3. Check for interference (other WiFi networks)

**Cannot connect to router:**
1. Reset router: Hold reset button 10 seconds
2. Reconfigure from Step 2

---

## Step 9: Optional Advanced Settings

### 9.1 Enable Router Logging

Navigate to: **System Tools → Log**

- Enable log to see device connections/disconnections

### 9.2 Backup Configuration

Navigate to: **System Tools → Backup & Restore**

- Save configuration to file
- Store on laptop for quick restore

### 9.3 Set Static DNS (Optional)

Navigate to: **Network → WAN** or **DHCP → DHCP Settings**

If you want internet via phone hotspot:
- Connect router to phone via Ethernet (adapter needed)
- Set DNS to 8.8.8.8 and 8.8.4.4

---

## Network Diagram

```
                    Power Bank
                         │
                         ▼
              ┌─────────────────────┐
              │   TP-Link Router    │
              │   192.168.0.1       │
              │   SSID: SprintRace  │
              └──────────┬──────────┘
                         │
         ┌───────────────┼───────────────┐
         │               │               │
         ▼               ▼               ▼
┌────────────────┐ ┌──────────┐ ┌──────────────┐
│  Host Tablet   │ │ Display  │ │  Start Phone │
│ 192.168.0.10   │ │ 192.168.0.13 │ 192.168.0.11 │
│ Race Controller│ │ Results  │ │ Start Line   │
└────────────────┘ └──────────┘ └──────────────┘
         │
         │  ┌──────────────┐
         └──►│  Stop Phone  │
            │ 192.168.0.12 │
            │ Finish Line  │
            └──────────────┘
```

---

## Quick Reference Card

**Router Login:**
- URL: http://192.168.0.1
- Username: admin
- Password: [your admin password]

**WiFi Settings:**
- SSID: SprintRace
- Password: race2024
- Band: 5GHz preferred

**Device IPs:**
- Host: 192.168.0.10
- Start: 192.168.0.11
- Stop: 192.168.0.12
- Display: 192.168.0.13
- Split: 192.168.0.14

**Troubleshooting:**
- Reboot router: Unplug 10 seconds
- Reset router: Hold reset 10 seconds
- Check IPs: Settings → About → Status

---

## FAQ

**Q: Can I use any router?**
A: Yes, but travel routers like TL-WR902AC are compact and battery-powered. Any router supporting static DHCP works.

**Q: Do I need internet?**
A: No. SprintSync works offline on local network only.

**Q: What if a device gets a different IP?**
A: Check DHCP reservation MAC address matches the device. If wrong, update reservation.

**Q: Can I use 2.4GHz instead?**
A: Yes, but 5GHz has less interference and lower latency. Use 2.4GHz only if 5GHz range is insufficient.

**Q: How many devices can connect?**
A: TL-WR902AC supports 15+ simultaneous connections easily.

---

## Summary

After completing this setup:
- All devices have **static IP addresses**
- **Low latency** (1-5ms) between devices
- **No discovery delays** - devices connect instantly
- **Offline operation** - no internet needed
- **Consistent configuration** - same setup every race

Your SprintSync race timing system is now optimized for speed and reliability!
