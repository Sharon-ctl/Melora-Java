const puppeteer = require('puppeteer-core');
const fs = require('fs');

(async () => {
    console.log("Connecting to Chrome on port 9222...");
    const browser = await puppeteer.connect({ browserURL: 'http://localhost:9222' });
    
    const pages = await browser.pages();
    const page = pages.find(p => p.url().includes('youtube.com'));
    
    if (!page) {
        console.log("YouTube tab not found!");
        process.exit(1);
    }
    
    console.log("Connected to YouTube tab!");
    console.log("Waiting for a videoplayback request (usually happens every few seconds while a video is playing)...");

    let found = false;

    page.on('request', request => {
        const url = request.url();
        if (url.includes('videoplayback')) {
            const urlObj = new URL(url);
            const poToken = urlObj.searchParams.get('potoken') || urlObj.searchParams.get('pot');
            const visitorData = urlObj.searchParams.get('vis') || urlObj.searchParams.get('visitorData');
            
            if (poToken) {
                found = true;
                console.log("\n=================================");
                console.log("🎉 SUCCESSFULLY EXTRACTED TOKENS! 🎉");
                console.log("=================================");
                console.log("poToken:", poToken);
                
                let envContent = fs.readFileSync('.env', 'utf8');
                envContent = envContent.replace(/YOUTUBE_PO_TOKEN=.*/g, `YOUTUBE_PO_TOKEN=${poToken}`);
                
                // Try to get visitor data from page if it's missing from URL
                page.evaluate(() => {
                    try {
                        return window.ytcfg.get('INNERTUBE_CONTEXT').client.visitorData;
                    } catch(e) { return null; }
                }).then(visData => {
                    const finalVis = visitorData || visData || "INSERT_VISITOR_DATA";
                    console.log("visitorData:", finalVis);
                    envContent = envContent.replace(/YOUTUBE_VISITOR_DATA=.*/g, `YOUTUBE_VISITOR_DATA=${finalVis}`);
                    fs.writeFileSync('.env', envContent);
                    console.log("Tokens automatically saved to .env file! You can close Chrome now.");
                    process.exit(0);
                });
            }
        }
    });

    // To force a videoplayback request, we can just seek the video forward slightly
    console.log("Seeking video to force a network request...");
    await page.evaluate(() => {
        const video = document.querySelector('video');
        if (video) {
            video.currentTime = video.currentTime + 1;
        }
    });

    setTimeout(() => {
        if (!found) {
            console.log("Timeout: Could not find potoken in network requests.");
            process.exit(1);
        }
    }, 15000);

})();
