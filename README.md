# TLV Finder — Android App

Check if Tel Aviv apartment listings are inside your desired area.

## Setup

1. Add your Claude API key as a GitHub Secret:
   - Go to repo Settings → Secrets and variables → Actions
   - Click "New repository secret"
   - Name: `CLAUDE_API_KEY`
   - Value: your Claude API key (starts with `sk-ant-...`)

2. Push all files to the `main` branch.

3. GitHub Actions will automatically build the APK.

4. Download the APK from Actions → latest run → Artifacts → TLVFinder.

5. Transfer APK to your phone and install it (you may need to allow "Install from unknown sources" in Settings).

## Usage

- Open the app and draw your desired area on the map.
- When browsing Facebook, tap Share on any apartment post → TLV Finder.
- The app will automatically extract the address and show you if it's in your area.
