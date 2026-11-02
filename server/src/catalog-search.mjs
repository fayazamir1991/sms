export const normalizeSearch=value=>value.normalize('NFKC').replace(/[\u064b-\u065f\u0670]/g,'').replace(/ي/g,'ی').replace(/ك/g,'ک').replace(/\u200c/g,' ').toLowerCase();
export const compactSearch=value=>normalizeSearch(value).replace(/[\s\u200c\u200d]+/g,'');
export const likePattern=value=>'%'+compactSearch(value).replace(/[\\%_]/g,c=>'\\'+c)+'%';

// Existing search_text rows are already normalized; compact whitespace at query time
// so earlier deployments need no destructive migration or search-index rebuild.
export const compactSearchColumn=column=>["' '",'char(9)','char(10)','char(13)','char(160)','char(8204)','char(8205)'].reduce((sql,ch)=>`replace(${sql},${ch},'')`,column);
