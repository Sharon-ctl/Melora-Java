const { generate } = require('youtube-po-token-generator');
const puppeteer = require('puppeteer-core');
const fs = require('fs');

const chromePath = 'C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe';

generate({
    executablePath: chromePath
}).then(tokens => {
    console.log("poToken:", tokens.poToken);
    console.log("visitorData:", tokens.visitorData);
    
    let envContent = fs.readFileSync('.env', 'utf8');
    envContent = envContent.replace(/YOUTUBE_PO_TOKEN=.*/g, `YOUTUBE_PO_TOKEN=${tokens.poToken}`);
    envContent = envContent.replace(/YOUTUBE_VISITOR_DATA=.*/g, `YOUTUBE_VISITOR_DATA=${tokens.visitorData}`);
    fs.writeFileSync('.env', envContent);
    console.log("Tokens updated in .env!");
    process.exit(0);
}).catch(err => {
    console.error(err);
    process.exit(1);
});
