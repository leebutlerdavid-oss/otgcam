# How to Build OTGCam APKs Using GitHub (No Android Studio Required)

This guide shows you how to use GitHub's free cloud servers to build both APKs automatically. You don't need Android Studio or any software on your computer.

---

## Step 1: Create a GitHub Account

1. Go to https://github.com
2. Click **Sign up** (top right)
3. Enter your email, create a password, choose a username
4. Verify your email

---

## Step 2: Create a New Repository

1. Log into GitHub
2. Click the **+** button (top right) → **New repository**
3. Name it: `otgcam`
4. Choose **Public** (free) or **Private** (also free)
5. Check **Add a README file**
6. Click **Create repository**

---

## Step 3: Upload the Project Files

### Option A: Download and Upload My Pre-made Repo (Easiest)

1. Download this file: [otgcam-github-repo.zip](sandbox:///mnt/agents/output/otgcam-github-repo.zip)
2. Extract it on your computer
3. On your GitHub repo page, click **Add file** → **Upload files**
4. Drag ALL files from the extracted folder into the upload area
   - `otgcam-agent/` (folder)
   - `otgcam-receiver/` (folder)
   - `.github/workflows/build.yml` (folder)
   - `README.md`
   - `.gitignore`
5. Click **Commit changes**

### Option B: Use Git Command Line (For Advanced Users)

```bash
git clone https://github.com/YOUR_USERNAME/otgcam.git
cd otgcam
# Copy the project files here
git add .
git commit -m "Initial commit"
git push origin main
```

---

## Step 4: Trigger the Build

1. On your GitHub repo page, click **Actions** tab
2. You should see the workflow "Build OTGCam APKs"
3. If it hasn't started automatically, click **Run workflow** → **Run workflow**
4. Wait 5–10 minutes for the build to complete

---

## Step 5: Download Your APKs

1. Once the workflow is green (✓), click on the completed run
2. Scroll down to the **Artifacts** section
3. Download:
   - `otgcam-agent-apk` → This is your field device app
   - `otgcam-receiver-apk` → This is your remote operator app
4. Unzip the downloaded files to get the `.apk` files

---

## Step 6: Install on Your Phones

### Transfer APKs to Phone
- **Email:** Email the APK to yourself, open on phone
- **USB Cable:** Connect phone to computer, copy APK to Downloads folder
- **Cloud:** Upload to Google Drive/Dropbox, download on phone
- **Bluetooth:** Send directly from computer to phone

### Install
1. On the phone, open **Files** app
2. Navigate to the APK file
3. Tap it
4. If prompted, allow **Install from unknown sources**
5. Tap **Install**

---

## Troubleshooting GitHub Actions

| Problem | Solution |
|---|---|
| Workflow not showing | Make sure `.github/workflows/build.yml` was uploaded correctly |
| Build failed (red X) | Click the failed job, read the error log. Usually a dependency issue. |
| "Permission denied" | Make sure your repo is Public, or check Actions permissions in Settings |
| Artifacts not appearing | Wait for both jobs (agent + receiver) to finish |
| Build takes too long | Normal for first build. Subsequent builds are faster with caching. |

---

## Updating the Code

If you want to change anything later:

1. Edit files directly on GitHub (click the file → pencil icon)
2. Or edit on your computer and re-upload
3. GitHub Actions will automatically rebuild when you commit changes

---

## Alternative: GitHub Codespaces (Edit Code Online)

GitHub gives you a free online VS Code editor:

1. On your repo page, press `.` (period key) on your keyboard
2. VS Code opens in your browser
3. Edit any file
4. Changes are saved automatically to GitHub
5. The workflow will rebuild automatically

---

## Need Help?

- GitHub Actions documentation: https://docs.github.com/en/actions
- GitHub upload help: https://docs.github.com/en/repositories/working-with-files/managing-files/adding-a-file-to-a-repository
