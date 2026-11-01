export const normalizeSearch=value=>value.normalize('NFKC').replace(/[\u064b-\u065f\u0670]/g,'').replace(/ي/g,'ی').replace(/ك/g,'ک').replace(/\u200c/g,' ').toLowerCase();
export const likePattern=value=>'%'+normalizeSearch(value).replace(/[\\%_]/g,c=>'\\'+c)+'%';
