const puppeteer = require('puppeteer-core');
const fs = require('fs');

(async () => {
    console.log("Connecting to Chrome...");
    const browser = await puppeteer.connect({ browserURL: 'http://localhost:9222' });
    
    // Get all open pages and pick the youtube one
    const pages = await browser.pages();
    const page = pages.find(p => p.url().includes('youtube.com'));
    
    if (!page) {
        console.log("No YouTube tab found! Opening one...");
        const newPage = await browser.newPage();
        await newPage.goto("https://www.youtube.com");
        process.exit(1);
    }
    
    console.log("Connected to YouTube tab!");
    console.log("Reloading page to capture tokens...");
    
    let found = false;

    // We will listen for BOTH 'request' and response bodies to grab the token
    page.on('request', request => {
        const url = request.url();
        if (url.includes('youtubei/v1/player') || url.includes('youtubei/v1/next') || url.includes('videoplayback')) {
            const urlObj = new URL(url);
            const poToken = urlObj.searchParams.get('potoken') || urlObj.searchParams.get('pot') || urlObj.searchParams.get('po');
            const visitorData = urlObj.searchParams.get('visitorData') || urlObj.searchParams.get('vis');
            
            if (poToken && !found) {
                found = true;
                console.log("\n>>> FOUND TOKENS IN URL! <<<");
                console.log("poToken:", poToken);
                saveTokens(poToken, "INSERT_VISITOR_DATA");
            }
            
            const postData = request.postData();
            if (postData && !found) {
                try {
                    const data = JSON.parse(postData);
                    let actualPoToken = data.context?.client?.clientFormFactor?.webFormFactor?.pot || data.serviceTrackingParams?.[0]?.params?.find(p => p.key === 'pot')?.value;
                    let actualVisitorData = data.context?.client?.visitorData;

                    if (actualPoToken && actualVisitorData && !found) {
                        found = true;
                        console.log("\n=================================");
                        console.log("🎉 SUCCESSFULLY EXTRACTED TOKENS! 🎉");
                        console.log("=================================");
                        console.log("poToken:", actualPoToken);
                        console.log("visitorData:", actualVisitorData);
                        saveTokens(actualPoToken, actualVisitorData);
                    }
                } catch (e) {}
            }
        }
    });
    
    function saveTokens(poToken, visitorData) {
        let envContent = '';
        try {
            envContent = fs.readFileSync('.env', 'utf8');
        } catch(e) {}
        
        if (envContent.includes('YOUTUBE_PO_TOKEN=')) {
            envContent = envContent.replace(/YOUTUBE_PO_TOKEN=.*/g, `YOUTUBE_PO_TOKEN=${poToken}`);
            envContent = envContent.replace(/YOUTUBE_VISITOR_DATA=.*/g, `YOUTUBE_VISITOR_DATA=${visitorData}`);
        } else {
            envContent += `\nYOUTUBE_PO_TOKEN=${poToken}\nYOUTUBE_VISITOR_DATA=${visitorData}\n`;
        }
        
        fs.writeFileSync('.env', envContent);
        console.log("Tokens saved to .env file! You can close Chrome now.");
        process.exit(0);
    }

    // Force reload to trigger the network requests
    await page.reload({ waitUntil: 'networkidle0' }).catch(() => {});
    
    // Fallback search in page context if network intercept missed it
    if (!found) {
        console.log("Searching page variables...");
        try {
            const data = await page.evaluate(() => {
                let pot = null;
                let vis = null;
                if (window.ytcfg) {
                    const cfg = window.ytcfg.get('INNERTUBE_CONTEXT');
                    if (cfg) {
                        vis = cfg.client?.visitorData;
                    }
                }
                return { vis, pot };
            });
            if (data.vis && !found) {
                console.log("Found visitorData via ytcfg:", data.vis);
                // Can't easily get poToken this way, let's keep waiting for network
            }
        } catch(e) {}
    }

    setTimeout(() => {
        if (!found) process.exit(1);
    }, 15000);
})();
