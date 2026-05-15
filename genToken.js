const { generate } = require('youtube-po-token-generator');
const fs = require('fs');

generate().then(tokens => {
    console.log("🎉 GENERATED TOKENS SUCCESSFULLY! 🎉");
    console.log("poToken:", tokens.poToken);
    console.log("visitorData:", tokens.visitorData);
    
    let envContent = '';
    try {
        envContent = fs.readFileSync('.env', 'utf8');
    } catch(e) {}
    
    if (envContent.includes('YOUTUBE_PO_TOKEN=')) {
        envContent = envContent.replace(/YOUTUBE_PO_TOKEN=.*/g, `YOUTUBE_PO_TOKEN=${tokens.poToken}`);
        envContent = envContent.replace(/YOUTUBE_VISITOR_DATA=.*/g, `YOUTUBE_VISITOR_DATA=${tokens.visitorData}`);
    } else {
        envContent += `\nYOUTUBE_PO_TOKEN=${tokens.poToken}\nYOUTUBE_VISITOR_DATA=${tokens.visitorData}\n`;
    }
    
    fs.writeFileSync('.env', envContent);
    console.log("\nTokens automatically saved to .env file!");
    process.exit(0);
}).catch(err => {
    console.error("Error generating tokens:", err);
    process.exit(1);
});
